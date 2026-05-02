import base64
import json
import os
import tempfile
from typing import Any, Dict, List, Optional

from dotenv import load_dotenv
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from openai import AsyncOpenAI
from pydantic import BaseModel, Field

load_dotenv()

OPENAI_API_KEY = os.getenv("OPENAI_API_KEY")
TRANSCRIBE_MODEL = os.getenv("OPENAI_TRANSCRIBE_MODEL", "gpt-4o-transcribe")
PLANNER_MODEL = os.getenv("OPENAI_PLANNER_MODEL", "gpt-5.4-nano")
VISION_MODEL = os.getenv("OPENAI_VISION_MODEL", "gpt-5.4")
DEBUG_RETAIN_UPLOADS = os.getenv("DEBUG_RETAIN_UPLOADS", "false").lower() == "true"
MAX_AUDIO_BYTES = 25 * 1024 * 1024
MAX_IMAGE_BYTES = 10 * 1024 * 1024

app = FastAPI(title="DroidLM Relay", version="0.1.3")
client = AsyncOpenAI(api_key=OPENAI_API_KEY) if OPENAI_API_KEY else None


class PlanActionRequest(BaseModel):
    goal: str
    uiState: Dict[str, Any] = Field(default_factory=dict)
    packages: List[Dict[str, Any]] = Field(default_factory=list)
    history: List[str] = Field(default_factory=list)
    maxSteps: int = 12


ACTION_SCHEMA = {
    "type": "object",
    "properties": {
        "action": {
            "type": "string",
            "enum": [
                "OPEN_APP", "OPEN_SETTINGS", "TAP", "LONG_PRESS", "SWIPE", "TYPE_TEXT",
                "GLOBAL_BACK", "GLOBAL_HOME", "TAKE_SCREENSHOT", "FOCUS_EDITABLE",
                "SET_SELECTION", "INSERT_TEXT", "REPLACE_SELECTION", "SET_FULL_TEXT",
                "MOVE_CURSOR", "TAP_TEXT_ANCHOR", "OCR_SCREEN", "ANALYZE_SCREENSHOT",
                "INSERT_TEXT_AT_ANCHOR", "REPLACE_TEXT_RANGE", "APPEND_TEXT", "PREPEND_TEXT",
                "SELECT_ALL", "DELETE_SELECTED_TEXT", "VERIFY_TEXT_CHANGE", "ASK_CONFIRMATION",
                "DONE", "NO_OP"
            ],
        },
        "reason": {"type": "string"},
        "requiresConfirmation": {"type": "boolean"},
        "appName": {"type": "string"},
        "packageName": {"type": "string"},
        "x": {"type": "integer"},
        "y": {"type": "integer"},
        "startX": {"type": "integer"},
        "startY": {"type": "integer"},
        "endX": {"type": "integer"},
        "endY": {"type": "integer"},
        "durationMs": {"type": "integer"},
        "text": {"type": "string"},
        "nodeId": {"type": "string"},
        "start": {"type": "integer"},
        "end": {"type": "integer"},
        "targetDescription": {"type": "string"},
        "anchorText": {"type": "string"},
        "anchorPosition": {"type": "string", "enum": ["BEFORE", "AFTER"]},
        "goal": {"type": "string"},
        "expectedText": {"type": "string"},
        "targetText": {"type": "string"},
        "replacementText": {"type": "string"},
        "confirmationPrompt": {"type": "string"},
    },
    "required": ["action", "reason", "requiresConfirmation"],
    "additionalProperties": True,
}


@app.get("/health")
async def health() -> Dict[str, bool]:
    return {"ok": True}


@app.post("/transcribe")
async def transcribe(
    audio: UploadFile = File(...),
    language: Optional[str] = Form(default=None),
    modelHint: Optional[str] = Form(default=None),
) -> Dict[str, Any]:
    openai_client = require_openai()
    content_type = (audio.content_type or "").lower()
    if not content_type.startswith("audio/") and content_type not in {"application/octet-stream"}:
        raise HTTPException(status_code=415, detail="Only audio uploads are accepted")
    data = await audio.read()
    if not data:
        raise HTTPException(status_code=400, detail="Audio upload is empty")
    if len(data) > MAX_AUDIO_BYTES:
        raise HTTPException(status_code=413, detail="Audio upload is too large")

    suffix = os.path.splitext(audio.filename or "command.m4a")[1] or ".m4a"
    temp_path = write_temp(data, suffix)
    try:
        with open(temp_path, "rb") as handle:
            result = await openai_client.audio.transcriptions.create(
                model=modelHint or TRANSCRIBE_MODEL,
                file=handle,
                language=language,
            )
        return {"text": getattr(result, "text", "").strip()}
    finally:
        cleanup_temp(temp_path)


@app.post("/plan-action")
async def plan_action(payload: PlanActionRequest) -> Dict[str, Any]:
    openai_client = require_openai()
    system = (
        "You control an Android device through a limited tool interface. Return one JSON action only. "
        "Prefer safe, minimal actions. Ask for confirmation for risky operations. Never claim you executed an action. "
        "Use DONE when task is complete. Do not request actions outside the available Android tool schema."
    )
    user = {
        "goal": payload.goal,
        "uiState": payload.uiState,
        "packages": payload.packages[:200],
        "history": payload.history[-20:],
        "maxSteps": payload.maxSteps,
    }
    response = await openai_client.chat.completions.create(
        model=PLANNER_MODEL,
        messages=[
            {"role": "system", "content": system},
            {"role": "user", "content": json.dumps(user, ensure_ascii=False)},
        ],
        response_format={
            "type": "json_schema",
            "json_schema": {"name": "droidlm_action", "schema": ACTION_SCHEMA, "strict": False},
        },
    )
    content = response.choices[0].message.content or "{}"
    return json.loads(content)


@app.post("/analyze-screenshot")
async def analyze_screenshot(
    image: UploadFile = File(...),
    goal: str = Form(...),
    uiState: Optional[str] = Form(default=None),
) -> Dict[str, Any]:
    openai_client = require_openai()
    content_type = (image.content_type or "").lower()
    if content_type not in {"image/png", "image/jpeg", "image/webp"}:
        raise HTTPException(status_code=415, detail="Only PNG/JPEG/WebP screenshots are accepted")
    data = await image.read()
    if not data:
        raise HTTPException(status_code=400, detail="Screenshot upload is empty")
    if len(data) > MAX_IMAGE_BYTES:
        raise HTTPException(status_code=413, detail="Screenshot upload is too large")

    encoded = base64.b64encode(data).decode("ascii")
    prompt = (
        "Analyze this Android screenshot for the user's goal. Return JSON only with fullText, suggestedAction, lines, and elements. "
        "Use suggestedAction only if a coordinate is reasonably clear. Bounding boxes use x, y, width, height. "
        f"Goal: {goal}\nUI state: {uiState or '{}'}"
    )
    response = await openai_client.chat.completions.create(
        model=VISION_MODEL,
        messages=[
            {"role": "system", "content": "Return structured JSON only. Do not retain or reveal sensitive content beyond text needed for the user's task."},
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": prompt},
                    {"type": "image_url", "image_url": {"url": f"data:{content_type};base64,{encoded}"}},
                ],
            },
        ],
        response_format={"type": "json_object"},
    )
    content = response.choices[0].message.content or "{}"
    parsed = json.loads(content)
    return {
        "fullText": parsed.get("fullText", ""),
        "suggestedAction": parsed.get("suggestedAction"),
        "lines": parsed.get("lines", []),
        "elements": parsed.get("elements", []),
    }


@app.post("/realtime-token")
async def realtime_token() -> Dict[str, str]:
    require_openai()
    return {"message": "Ephemeral realtime token generation is not implemented in the MVP"}


def require_openai() -> AsyncOpenAI:
    if client is None:
        raise HTTPException(status_code=500, detail="OPENAI_API_KEY is not configured")
    return client


def write_temp(data: bytes, suffix: str) -> str:
    handle = tempfile.NamedTemporaryFile(delete=False, suffix=suffix)
    with handle:
        handle.write(data)
    return handle.name


def cleanup_temp(path: str) -> None:
    if DEBUG_RETAIN_UPLOADS:
        return
    try:
        os.remove(path)
    except OSError:
        pass
