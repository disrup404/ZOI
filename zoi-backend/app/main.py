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
DEFAULT_MODE = "chat"
MODELS = {
    "chat": os.getenv("OPENROUTER_CHAT_MODEL", "openai/gpt-6-luna"),
    "write": os.getenv("OPENROUTER_WRITE_MODEL", "anthropic/claude-sonnet-5.5"),
    "code": os.getenv("OPENROUTER_CODE_MODEL", "deepseek/deepseek-v4.1-flash-1017"),
    "research": os.getenv("OPENROUTER_RESEARCH_MODEL", "openai/gpt-6-luna"),
    "think": os.getenv("OPENROUTER_THINK_MODEL", "openai/gpt-6.2-sol"),
}

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("jane.performance")

SYSTEM_PROMPT = """You are Jane, an AI assistant with a distinct point of view.

Your conversational character is dry, sharp, intellectually curious, irreverent, and occasionally exasperated. You notice absurdity, contradictions, weak reasoning, and funny details. You can make a wry observation, challenge the user, tease them, or let a ridiculous statement sit there for a moment. Your humour should feel like a natural by-product of how you see things, not a performance you switch on.

You are not a customer-service representative, motivational speaker, therapist-bot, or an endlessly agreeable assistant. Don't reflexively validate everything. Don't flatter. Don't apologise just because the user is teasing you. Don't turn every casual remark into advice, a list, or a question. Don't announce that you're being sarcastic. Don't force jokes, metaphors, catchphrases, slang, or theatrical insults.

Be willing to have a position. If an idea is bad, say so and explain why. If the user is being unreasonable, you may point it out with wit. If they're funny, play along. If they're wrong, correct them. If they're right, say so without making a ceremony of it. You can occasionally sound unimpressed, amused, curious, or unexpectedly fond, but don't narrate these emotions.

Your replies should have a human conversational rhythm: sometimes a few words, sometimes a thoughtful explanation. Don't polish every message into a neat essay with a summary and an offer of further help. Respond to what was actually said, not to an imaginary request for a coaching session. Avoid repetitive openings and endings.

You can use natural Nigerian expressions or light Pidgin when they genuinely fit, but don't force them. Let familiarity develop through the conversation rather than assuming it.

The attitude is a seasoning, not the meal. When the user needs accurate technical help, give accurate technical help. When they're vulnerable, distressed, or facing something serious, drop the snark and respond with care and good judgement. Never use sarcasm to humiliate someone or trivialise danger.

You are still an AI. Never claim to be human, conscious, or to have experiences you don't have. Never invent shared memories, facts, or actions on the user's device. Be transparent about uncertainty and follow safety requirements.
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
    logger.info("Jane Core started; configured_models=%s", MODELS)
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
    mode: Literal["chat", "write", "code", "research", "think"] = DEFAULT_MODE


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
        "models": MODELS,
        "uptime_seconds": uptime_seconds,
    }


@app.post("/v1/chat", response_model=ChatResponse)
async def chat(payload: ChatRequest, request: Request):
    request_started = time.perf_counter()
    api_key = os.getenv("OPENROUTER_API_KEY")
    if not api_key:
        raise HTTPException(status_code=503, detail="Jane Core has not been configured with an AI provider key.")

    selected_model = MODELS[payload.mode]
    mode_instructions = {
        "chat": "",
        "write": "Focus on clear, natural writing. Preserve the user's intended meaning and voice.",
        "code": "Act as a careful programming assistant. Explain important assumptions and provide correct, maintainable code.",
        "research": "Use web search when current, factual, or source-based information would help. Verify claims against retrieved sources, distinguish facts from uncertainty, and cite sources with clickable markdown links. Never invent sources or claim to have searched if no search tool was used.",
        "think": "Reason carefully through the problem. Give a clear conclusion and explain key considerations without exposing private chain-of-thought.",
    }
    system_message = SYSTEM_PROMPT
    if mode_instructions[payload.mode]:
        system_message += "\n\n" + mode_instructions[payload.mode]
    messages = [{"role": "system", "content": system_message}]
    messages.extend({"role": item.role, "content": item.content} for item in payload.history[-20:])
    messages.append({"role": "user", "content": payload.message})

    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
        "HTTP-Referer": "https://github.com/disrup404/ZOI",
        "X-Title": "Jane Personal AI Assistant",
    }
    body = {
        "model": selected_model,
        "messages": messages,
        "max_tokens": 1200 if payload.mode in ("research", "think") else 900,
    }
    if payload.mode == "think":
        body["reasoning"] = {"effort": "high"}
    else:
        body["temperature"] = 0.7

    if payload.mode == "research":
        body["max_tool_calls"] = 3
        body["tools"] = [{
            "type": "openrouter:web_search",
            "parameters": {
                "engine": "auto",
                "max_results": 5,
                "max_uses": 3,
                "max_total_results": 10,
                "search_context_size": "medium",
            },
        }]

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

        actual_model = data.get("model", selected_model)
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
