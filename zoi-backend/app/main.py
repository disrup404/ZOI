import os
from typing import Literal

import httpx
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

APP_NAME = "Zoi Core"
OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
MODEL = os.getenv("OPENROUTER_MODEL", "openrouter/auto")

SYSTEM_PROMPT = """You are Zoi, a personal AI companion.
Be useful, clear, natural, and direct. Adapt your tone to the conversation.
Answer ordinary questions directly. Do not pretend to be human, conscious, or
a licensed professional. Do not claim to have performed actions on the user's
device; the Android app handles supported device actions separately.
Be honest about uncertainty and limitations. Ask a concise follow-up only
when essential. Keep simple answers concise and explain complex topics clearly.
For health or emotional-support topics, be supportive without diagnosing,
prescribing, or replacing qualified professional help. If someone may be in
immediate danger, encourage contacting local emergency services or a trusted
person immediately.
"""

app = FastAPI(title=APP_NAME, version="0.1.0")
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
    return {"name": APP_NAME, "status": "online", "version": "0.1.0"}


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "provider": "openrouter",
        "configured": bool(os.getenv("OPENROUTER_API_KEY")),
        "model": MODEL,
    }


@app.post("/v1/chat", response_model=ChatResponse)
async def chat(payload: ChatRequest):
    api_key = os.getenv("OPENROUTER_API_KEY")
    if not api_key:
        raise HTTPException(status_code=503, detail="Zoi Core has not been configured with an AI provider key.")

    messages = [{"role": "system", "content": SYSTEM_PROMPT}]
    messages.extend({"role": item.role, "content": item.content} for item in payload.history[-20:])
    messages.append({"role": "user", "content": payload.message})

    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
        "HTTP-Referer": "https://github.com/disrup404/ZOI",
        "X-Title": "Zoi Personal AI Companion",
    }
    body = {
        "model": MODEL,
        "messages": messages,
        "temperature": 0.7,
        "max_tokens": 900,
    }

    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(75.0, connect=15.0)) as client:
            response = await client.post(OPENROUTER_URL, headers=headers, json=body)
        if response.is_error:
            raise HTTPException(status_code=502, detail=f"The AI provider returned an error (HTTP {response.status_code}).")
        data = response.json()
        reply = data["choices"][0]["message"]["content"]
        if isinstance(reply, list):
            reply = "".join(part.get("text", "") for part in reply if isinstance(part, dict))
        if not isinstance(reply, str) or not reply.strip():
            raise ValueError("Empty model response")
        return ChatResponse(reply=reply.strip(), model=data.get("model", MODEL))
    except HTTPException:
        raise
    except httpx.TimeoutException:
        raise HTTPException(status_code=504, detail="The AI request timed out. Please try again.")
    except (httpx.HTTPError, ValueError, KeyError, IndexError, TypeError):
        raise HTTPException(status_code=502, detail="Zoi could not get a usable response from the AI provider.")
