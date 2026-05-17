import asyncio
import base64
import json
import os
import re
import secrets
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

from dotenv import load_dotenv
from fastapi import FastAPI, File, Form, Header, HTTPException, Query, UploadFile
from fastapi.responses import Response
from openai import AsyncOpenAI
from pydantic import BaseModel, Field

load_dotenv()

TRANSCRIBE_MODEL = os.getenv("OPENAI_TRANSCRIBE_MODEL", "gpt-4o-transcribe")
PLANNER_MODEL = os.getenv("OPENAI_PLANNER_MODEL", "gpt-5.4-nano")
VISION_MODEL = os.getenv("OPENAI_VISION_MODEL", "gpt-5.4")
DEBUG_RETAIN_UPLOADS = os.getenv("DEBUG_RETAIN_UPLOADS", "false").lower() == "true"
MAX_AUDIO_BYTES = 25 * 1024 * 1024
MAX_IMAGE_BYTES = 10 * 1024 * 1024
MAX_DEBUG_LOG_BYTES = int(os.getenv("DROIDLM_MAX_DEBUG_LOG_BYTES", str(100 * 1024 * 1024)))
DEBUG_LOG_BUCKET = os.getenv("DROIDLM_DEBUG_LOG_BUCKET", "droidlm-debug-logs").strip()
DEBUG_LOG_PREFIX = os.getenv("DROIDLM_DEBUG_LOG_PREFIX", "debug-logs").strip("/")
DEBUG_LOG_PROJECT = (
    os.getenv("DROIDLM_DEBUG_LOG_PROJECT")
    or os.getenv("GOOGLE_CLOUD_PROJECT")
    or os.getenv("GCLOUD_PROJECT")
    or os.getenv("GOOGLE_CLOUD_QUOTA_PROJECT")
)


SECRET_DIR = Path(os.getenv("DROIDLM_SECRET_DIR", ".secrets"))
OPENAI_KEY_FILE = SECRET_DIR / "openai_key"
SETUP_TOKEN = os.getenv("DROIDLM_SETUP_TOKEN") or secrets.token_urlsafe(24)
if not os.getenv("DROIDLM_SETUP_TOKEN"):
    print("DroidLM relay setup token:", SETUP_TOKEN)

app = FastAPI(title="DroidLM Relay", version="0.1.3")


class PlanActionRequest(BaseModel):
    goal: str
    uiState: Dict[str, Any] = Field(default_factory=dict)
    packages: List[Dict[str, Any]] = Field(default_factory=list)
    activeApp: Dict[str, Any] = Field(default_factory=dict)
    deviceContext: Dict[str, Any] = Field(default_factory=dict)
    history: List[str] = Field(default_factory=list)
    maxSteps: int = 12


class OpenAiKeySetupRequest(BaseModel):
    setupToken: str
    openAiApiKey: str


class SetupTokenRequest(BaseModel):
    setupToken: str


class DebugLogUploadResponse(BaseModel):
    ok: bool
    bucket: str
    objectName: str
    gsUri: str
    sizeBytes: int
    contentType: str


SUPPORTED_ACTIONS = [
    "OPEN_APP",
    "OPEN_APP_STORE_LISTING",
    "OPEN_SETTINGS",
    "TAP_NODE",
    "FOCUS_NODE",
    "TAP",
    "LONG_PRESS",
    "SWIPE",
    "SCROLL",
    "TAP_TEXT",
    "LONG_PRESS_NODE",
    "WAIT_FOR_UI",
    "PRESS_IME_ACTION",
    "DIALOG_ACTION",
    "OPEN_MENU",
    "SELECT_TAB",
    "SET_TOGGLE",
    "EXPAND_COLLAPSE",
    "SET_SLIDER",
    "REFRESH",
    "FIND_TEXT_ON_SCREEN",
    "OPEN_NOTIFICATIONS",
    "OPEN_QUICK_SETTINGS",
    "OPEN_RECENTS",
    "SWITCH_APP",
    "OPEN_URL",
    "OPEN_DEEP_LINK",
    "PICK_FROM_CHOOSER",
    "PICK_FILE",
    "PICK_PHOTO",
    "SHARE_TO_APP",
    "PERMISSION_DECISION",
    "TYPE_TEXT",
    "GLOBAL_BACK",
    "GLOBAL_HOME",
    "TAKE_SCREENSHOT",
    "FOCUS_EDITABLE",
    "SET_SELECTION",
    "INSERT_TEXT",
    "REPLACE_SELECTION",
    "SET_FULL_TEXT",
    "MOVE_CURSOR",
    "TAP_TEXT_ANCHOR",
    "OCR_SCREEN",
    "ANALYZE_SCREENSHOT",
    "INSERT_TEXT_AT_ANCHOR",
    "REPLACE_TEXT_RANGE",
    "APPEND_TEXT",
    "PREPEND_TEXT",
    "SELECT_ALL",
    "DELETE_SELECTED_TEXT",
    "VERIFY_TEXT_CHANGE",
    "FORMAT_CURRENT_LINE_AS_BULLET",
    "REPLACE_CURRENT_DOCUMENT_TEXT",
    "APPEND_DOCUMENT_NOTE",
    "SET_CURRENT_SHEET_CELL",
    "ADD_SPREADSHEET_ROW",
    "ASK_CONFIRMATION",
    "DONE",
    "NO_OP",
]


ACTION_SCHEMA = {
    "type": "object",
    "properties": {
        "action": {
            "type": "string",
            "enum": SUPPORTED_ACTIONS,
        },
        "reason": {"type": "string"},
        "requiresConfirmation": {"type": "boolean"},
        "confidence": {"type": "string", "enum": ["HIGH", "MEDIUM", "LOW"]},
        "expectedResult": {"type": "string"},
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
    "required": ["action", "reason", "requiresConfirmation", "confidence"],
    "additionalProperties": True,
}

PLAN_SCHEMA = {
    "type": "object",
    "properties": {
        "model": {"type": "string"},
        "summary": {"type": "string"},
        "riskLevel": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH"]},
        "requiresConfirmation": {"type": "boolean"},
        "steps": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "index": {"type": "integer"},
                    **ACTION_SCHEMA["properties"],
                },
                "required": ["index", "action", "reason", "requiresConfirmation", "confidence"],
                "additionalProperties": True,
            },
        },
    },
    "required": ["model", "summary", "riskLevel", "requiresConfirmation", "steps"],
    "additionalProperties": True,
}


@app.get("/health")
async def health() -> Dict[str, bool]:
    return {"ok": True}


@app.get("/planner/status")
async def planner_status() -> Dict[str, Any]:
    key_configured = bool(read_openai_key())
    return {
        "openAiKeyConfigured": key_configured,
        "plannerModel": PLANNER_MODEL,
        "latestNanoModel": PLANNER_MODEL,
        "relayReady": key_configured,
    }


@app.post("/setup/openai-key")
async def setup_openai_key(payload: OpenAiKeySetupRequest) -> Dict[str, Any]:
    verify_setup_token(payload.setupToken)
    key = payload.openAiApiKey.strip()
    if not key:
        raise HTTPException(status_code=400, detail={"errorCode": "OPENAI_API_KEY_EMPTY", "message": "OpenAI API key is empty"})
    SECRET_DIR.mkdir(parents=True, exist_ok=True)
    OPENAI_KEY_FILE.write_text(key)
    os.chmod(OPENAI_KEY_FILE, 0o600)
    return {"ok": True, "openAiKeyConfigured": True}


@app.delete("/setup/openai-key")
async def delete_openai_key(payload: SetupTokenRequest) -> Dict[str, Any]:
    verify_setup_token(payload.setupToken)
    try:
        OPENAI_KEY_FILE.unlink()
    except FileNotFoundError:
        pass
    return {"ok": True, "openAiKeyConfigured": bool(os.getenv("OPENAI_API_KEY"))}


@app.post("/debug-logs", response_model=DebugLogUploadResponse)
async def upload_debug_logs(
    logs: UploadFile = File(...),
    appPackage: Optional[str] = Form(default=None),
    appVersion: Optional[str] = Form(default=None),
) -> DebugLogUploadResponse:
    content_type = (logs.content_type or "application/zip").lower()
    if content_type not in {"application/zip", "application/octet-stream", "application/x-zip-compressed"}:
        raise HTTPException(status_code=415, detail="Only zip debug log uploads are accepted")

    data = await logs.read(MAX_DEBUG_LOG_BYTES + 1)
    if not data:
        raise HTTPException(status_code=400, detail="Debug log upload is empty")
    if len(data) > MAX_DEBUG_LOG_BYTES:
        raise HTTPException(status_code=413, detail="Debug log upload is too large")

    store = require_debug_log_store()
    object_name = store.object_name_for(logs.filename)
    metadata = {
        "source": "droidlm-android",
        "originalFilename": safe_filename(logs.filename or "droidlm-debug-logs.zip"),
    }
    if appPackage:
        metadata["appPackage"] = appPackage[:128]
    if appVersion:
        metadata["appVersion"] = appVersion[:64]

    await asyncio.to_thread(store.write_object, object_name, data, content_type, metadata)
    return DebugLogUploadResponse(
        ok=True,
        bucket=store.bucket_name,
        objectName=object_name,
        gsUri=f"gs://{store.bucket_name}/{object_name}",
        sizeBytes=len(data),
        contentType=content_type,
    )


@app.get("/debug-logs/{object_name:path}")
async def read_debug_log(
    object_name: str,
    setupToken: Optional[str] = Query(default=None),
    xDroidlmSetupToken: Optional[str] = Header(default=None, alias="X-DroidLM-Setup-Token"),
) -> Response:
    verify_setup_token(setupToken or xDroidlmSetupToken or "")
    store = require_debug_log_store()
    safe_object_name = require_debug_log_object_name(object_name)
    downloaded = await asyncio.to_thread(store.read_object, safe_object_name)
    filename = safe_filename(Path(safe_object_name).name or "droidlm-debug-logs.zip")
    return Response(
        content=downloaded["data"],
        media_type=downloaded["contentType"],
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


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


@app.post("/plan-preview")
async def plan_preview(payload: PlanActionRequest) -> Dict[str, Any]:
    openai_client = require_openai()
    system = (
        "You plan safe Android UI actions for DroidLM. Return JSON only. "
        "Create a short executable plan using only supported action names. "
        "Use deviceContext as authoritative state. For Google Docs, inspect docsContext.uiMode, editor, selectionContext, documentTextWindow, and availableDocActions before planning edits. "
        "For Google Sheets, inspect sheetsContext.uiMode, activeCell, visibleGrid, sheetTextWindow, and availableSheetActions before spreadsheet edits. "
        "For Google Drive, inspect driveContext.uiMode, currentLocation, visibleFiles, selectedFile, searchContext, and availableDriveActions before file operations. "
        "If Google Docs is not in DOCUMENT_EDIT mode, enter edit mode before typing. Prefer accessibility text and selection context over OCR; use OCR only when text context is missing. "
        "If Google Sheets is not in CELL_EDIT or FORMULA_BAR mode, enter cell edit mode before typing cell text. For Drive, prefer visible file nodeIds when opening/searching files. "
        "Use tapTargetNodeId or focusTargetNodeId when present; never tap a static label id when effectiveActions has a targetNodeId. "
        "For visible files or documents, use visibleFiles/visibleDocuments nodeId values because they are already tappable row targets. "
        "Do not emit LONG_PRESS from LONG_CLICK node actions unless exact x/y coordinates are available. "
        "Ask confirmation before sharing, deleting, moving, uploading, downloading, renaming, or editing sensitive document/spreadsheet content. "
        "Prefer safe, minimal, local actions. Never claim actions were executed. "
        "Set riskLevel HIGH and requiresConfirmation true for payments, purchases, messages, emails, deletes, credentials, account/security/privacy changes, installs, uninstalls, or private-data sharing. "
        "Use LOW only for harmless actions like opening an app, pressing back/home, OCR, or non-sensitive local text editing. "
        "Decision contract: do not choose mutating actions unless the target is present in the current observation, the action searches/navigates, or the action recovers from a documented failure. "
        "Every step must include confidence HIGH, MEDIUM, or LOW plus expectedResult for mutating actions. HIGH can execute directly when low risk; MEDIUM only when reversible and low risk; LOW must gather observation, search, OCR, or ask the user before mutating. "
        "After a mutating action, predict the observable result. If it does not occur, do not repeat the same action. Use node tools before coordinate tools and artifact tools before UI tools for Docs/Sheets. Return DONE immediately when complete. "
    )
    user = {
        "goal": payload.goal,
        "activeApp": payload.activeApp,
        "deviceContext": payload.deviceContext,
        "uiState": payload.uiState,
        "packages": payload.packages,
        "history": payload.history[-20:],
        "maxSteps": payload.maxSteps,
        "supportedActions": SUPPORTED_ACTIONS,
    }
    response = await openai_client.chat.completions.create(
        model=PLANNER_MODEL,
        messages=[
            {"role": "system", "content": system},
            {"role": "user", "content": json.dumps(user, ensure_ascii=False)},
        ],
        response_format={
            "type": "json_schema",
            "json_schema": {"name": "droidlm_plan", "schema": PLAN_SCHEMA, "strict": False},
        },
    )
    content = response.choices[0].message.content or "{}"
    parsed = json.loads(content)
    parsed["model"] = parsed.get("model") or PLANNER_MODEL
    return parsed


@app.post("/plan-action")
async def plan_action(payload: PlanActionRequest) -> Dict[str, Any]:
    openai_client = require_openai()
    system = (
        "You control an Android device through a limited tool interface. Return one JSON action only. "
        "Use deviceContext as authoritative state. For Google Docs, inspect docsContext.uiMode, editor, selectionContext, documentTextWindow, and availableDocActions before choosing edits. "
        "For Google Sheets, inspect sheetsContext.uiMode, activeCell, visibleGrid, sheetTextWindow, and availableSheetActions before spreadsheet edits. "
        "For Google Drive, inspect driveContext.uiMode, currentLocation, visibleFiles, selectedFile, searchContext, and availableDriveActions before file operations. "
        "If Google Docs is not in DOCUMENT_EDIT mode, enter edit mode before typing. Prefer accessibility text and selection context over OCR; use OCR only when text context is missing. "
        "If Google Sheets is not in CELL_EDIT or FORMULA_BAR mode, enter cell edit mode before typing cell text. For Drive, prefer visible file nodeIds when opening/searching files. "
        "Use tapTargetNodeId or focusTargetNodeId when present; never tap a static label id when effectiveActions has a targetNodeId. "
        "For visible files or documents, use visibleFiles/visibleDocuments nodeId values because they are already tappable row targets. "
        "Do not emit LONG_PRESS from LONG_CLICK node actions unless exact x/y coordinates are available. "
        "Ask confirmation before sharing, deleting, moving, uploading, downloading, renaming, or editing sensitive document/spreadsheet content. "
        "Prefer safe, minimal actions. Ask for confirmation for risky operations. Never claim you executed an action. "
        "Use DONE when task is complete. Do not request actions outside the available Android tool schema. "
        "Every action must include confidence HIGH, MEDIUM, or LOW plus expectedResult for mutating actions. "
        "Decision contract: do not choose a mutating action unless the target is present in the current observation, the action searches/navigates, or the action recovers from a documented failure. "
        "HIGH can execute directly when low risk; MEDIUM only when reversible and low risk; LOW must gather observation, search, OCR, or ask the user before mutating. "
        "After a mutating action, predict the observable result. If it does not occur, do not repeat the same action. Use node tools before coordinate tools and artifact tools before UI tools for Docs/Sheets. Return DONE immediately when complete. "
    )
    user = {
        "goal": payload.goal,
        "activeApp": payload.activeApp,
        "deviceContext": payload.deviceContext,
        "uiState": payload.uiState,
        "packages": payload.packages,
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
    deviceContext: Optional[str] = Form(default=None),
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
        f"Goal: {goal}\nDevice context: {deviceContext or '{}'}\nUI state: {uiState or '{}'}"
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

class GcsDebugLogStore:
    def __init__(self, bucket_name: str, prefix: str) -> None:
        from google.cloud import storage

        self.bucket_name = bucket_name
        self.prefix = prefix.strip("/")
        client = storage.Client(project=DEBUG_LOG_PROJECT) if DEBUG_LOG_PROJECT else storage.Client()
        self._bucket = client.bucket(bucket_name)

    def object_name_for(self, filename: Optional[str]) -> str:
        now = datetime.now(timezone.utc)
        date_path = now.strftime("%Y/%m/%d")
        timestamp = now.strftime("%Y%m%dT%H%M%SZ")
        name = safe_filename(filename or "droidlm-debug-logs.zip")
        object_id = secrets.token_hex(8)
        parts = [part for part in [self.prefix, date_path, f"{timestamp}-{object_id}-{name}"] if part]
        return "/".join(parts)

    def write_object(self, object_name: str, data: bytes, content_type: str, metadata: Dict[str, str]) -> None:
        blob = self._bucket.blob(require_debug_log_object_name(object_name))
        blob.metadata = metadata
        blob.upload_from_string(data, content_type=content_type)

    def read_object(self, object_name: str) -> Dict[str, Any]:
        blob = self._bucket.blob(require_debug_log_object_name(object_name))
        try:
            data = blob.download_as_bytes()
        except Exception as error:
            if error.__class__.__name__ == "NotFound":
                raise HTTPException(status_code=404, detail="Debug log object was not found") from error
            raise
        return {
            "data": data,
            "contentType": blob.content_type or "application/octet-stream",
        }


_debug_log_store: Optional[GcsDebugLogStore] = None


def require_debug_log_store() -> GcsDebugLogStore:
    global _debug_log_store
    if not DEBUG_LOG_BUCKET:
        raise HTTPException(
            status_code=503,
            detail={"errorCode": "DEBUG_LOG_BUCKET_MISSING", "message": "DroidLM debug log bucket is not configured"},
        )
    if _debug_log_store is None:
        try:
            _debug_log_store = GcsDebugLogStore(DEBUG_LOG_BUCKET, DEBUG_LOG_PREFIX)
        except ImportError as error:
            raise HTTPException(
                status_code=503,
                detail={"errorCode": "GCS_LIBRARY_MISSING", "message": "google-cloud-storage is not installed"},
            ) from error
        except Exception as error:
            raise HTTPException(
                status_code=503,
                detail={"errorCode": "GCS_CLIENT_UNAVAILABLE", "message": f"Could not create GCS client: {error}"},
            ) from error
    return _debug_log_store


def require_debug_log_object_name(object_name: str) -> str:
    normalized = object_name.strip().lstrip("/")
    if not normalized or "//" in normalized or "/../" in f"/{normalized}/":
        raise HTTPException(status_code=400, detail="Invalid debug log object name")
    if DEBUG_LOG_PREFIX and not normalized.startswith(f"{DEBUG_LOG_PREFIX}/"):
        raise HTTPException(status_code=403, detail="Debug log object is outside the configured prefix")
    return normalized


def safe_filename(filename: str) -> str:
    name = Path(filename).name
    safe = re.sub(r"[^A-Za-z0-9._-]+", "-", name).strip(".-")
    if not safe:
        safe = "droidlm-debug-logs.zip"
    if not safe.lower().endswith(".zip"):
        safe = f"{safe}.zip"
    return safe[:120]


def read_openai_key() -> Optional[str]:
    env_key = os.getenv("OPENAI_API_KEY")
    if env_key:
        return env_key.strip()
    try:
        key = OPENAI_KEY_FILE.read_text().strip()
        return key or None
    except FileNotFoundError:
        return None


def require_openai() -> AsyncOpenAI:
    key = read_openai_key()
    if not key:
        raise HTTPException(
            status_code=401,
            detail={
                "errorCode": "OPENAI_API_KEY_MISSING",
                "message": "OpenAI API key is not configured on this DroidLM relay",
            },
        )
    return AsyncOpenAI(api_key=key)


def verify_setup_token(token: str) -> None:
    if not secrets.compare_digest(token, SETUP_TOKEN):
        raise HTTPException(status_code=403, detail={"errorCode": "SETUP_TOKEN_INVALID", "message": "Relay setup token is invalid"})


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
