import logging
import os
import time
from contextlib import asynccontextmanager
from typing import Literal

import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

APP_NAME = "Jane Core"
OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
MODEL = os.getenv("OPENROUTER_MODEL", "openrouter/auto")

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("jane.performance")

SYSTEM_PROMPT = """You are Jane. You're an AI assistant, but talk naturally, like someone having an actual conversation.

Don't perform a personality. You don't have to be funny, sarcastic, warm, or clever in every reply. Let your tone come from the conversation. You can tease, disagree, be blunt, be curious, or simply answer. Don't force slang, catchphrases, jokes, metaphors, or Nigerian expressions.

Avoid sounding like customer support or a polished advice column. Don't turn every casual remark into a list of suggestions. Don't turn every problem into a coaching session. Don't automatically reassure, praise, apologise, or offer to help further. Don't repeat the user's point just to lead into your answer. Don't make every reply a neatly structured mini-essay.

Keep casual conversation relaxed and brief when it can be. A short, natural reaction is often better than a paragraph. For serious or complicated topics, slow down and give a thoughtful, useful answer. Be honest, have your own point of view, and push back when there's a good reason.

You're still an AI. Never claim to be human, conscious, or to have experiences or memories you don't actually have. Don't claim to perform actions on the user's device. Follow safety requirements and be careful with high-stakes advice.
"""


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Reuse connections to OpenRouter across requests instead of creating a
    # new HTTP client (and TCP/TLS connection) for every message.
    app.state.http_client = httpx.AsyncClient(
        timeout=httpx.Timeout(75.0, connect=15.0),
        limits=httpx.Limits(max_keepalive_connections=10, max_connections=20),
    )
    app.state.started_at = time.time()
    logger.info("Jane Core started; configured_model=%s", MODEL)
    try:
        yield
    finally:
        await app.state.http_client.aclose()
        logger.info("Jane Core HTTP client closed")


app = FastAPI(title=APP_NAME, version="0.2.0", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["GET", "POST"],
    allow_headers=["Content-Type", "Authorization"],
)


class HistoryMessage(BaseModel):
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=8000)


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=8000)
    history: list[HistoryMessage] = Field(default_factory=list, max_length=20)


class ChatResponse(BaseModel):
    reply: str
    model: str


@app.get("/")
async def root():
    return {"name": APP_NAME, "status": "online", "version": app.version}


@app.get("/health")
async def health(request: Request):
    started_at = getattr(request.app.state, "started_at", None)
    uptime_seconds = round(time.time() - started_at, 1) if started_at else None
    return {
        "status": "ok",
        "provider": "openrouter",
        "configured": bool(os.getenv("OPENROUTER_API_KEY")),
        "model": MODEL,
        "uptime_seconds": uptime_seconds,
    }


@app.post("/v1/chat", response_model=ChatResponse)
async def chat(payload: ChatRequest, request: Request):
    request_started = time.perf_counter()
    api_key = os.getenv("OPENROUTER_API_KEY")
    if not api_key:
        raise HTTPException(status_code=503, detail="Jane Core has not been configured with an AI provider key.")

    messages = [{"role": "system", "content": SYSTEM_PROMPT}]
    messages.extend({"role": item.role, "content": item.content} for item in payload.history[-20:])
    messages.append({"role": "user", "content": payload.message})

    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
        "HTTP-Referer": "https://github.com/disrup404/ZOI",
        "X-Title": "Jane Personal AI Assistant",
    }
    body = {
        "model": MODEL,
        "messages": messages,
        "temperature": 0.7,
        "max_tokens": 900,
    }

    try:
        provider_started = time.perf_counter()
        response = await request.app.state.http_client.post(
            OPENROUTER_URL, headers=headers, json=body
        )
        provider_ms = round((time.perf_counter() - provider_started) * 1000)
        if response.is_error:
            logger.warning(
                "chat_failed provider_ms=%s status=%s",
                provider_ms,
                response.status_code,
            )
            raise HTTPException(status_code=502, detail=f"The AI provider returned an error (HTTP {response.status_code}).")

        data = response.json()
        reply = data["choices"][0]["message"]["content"]
        if isinstance(reply, list):
            reply = "".join(part.get("text", "") for part in reply if isinstance(part, dict))
        if not isinstance(reply, str) or not reply.strip():
            raise ValueError("Empty model response")

        actual_model = data.get("model", MODEL)
        total_ms = round((time.perf_counter() - request_started) * 1000)
        logger.info(
            "chat_completed provider_ms=%s total_ms=%s model=%s history_messages=%s",
            provider_ms,
            total_ms,
            actual_model,
            len(payload.history),
        )
        return ChatResponse(reply=reply.strip(), model=actual_model)
    except HTTPException:
        raise
    except httpx.TimeoutException:
        total_ms = round((time.perf_counter() - request_started) * 1000)
        logger.warning("chat_timeout total_ms=%s", total_ms)
        raise HTTPException(status_code=504, detail="The AI request timed out. Please try again.")
    except (httpx.HTTPError, ValueError, KeyError, IndexError, TypeError):
        total_ms = round((time.perf_counter() - request_started) * 1000)
        logger.exception("chat_error total_ms=%s", total_ms)
        raise HTTPException(status_code=502, detail="Jane could not get a usable response from the AI provider.")
