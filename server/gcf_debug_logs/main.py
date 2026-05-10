import json
import os
import re
import secrets
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Optional, Protocol

try:
    from dotenv import load_dotenv
except ImportError:  # pragma: no cover - deployment installs python-dotenv
    def load_dotenv(*_args: Any, **_kwargs: Any) -> bool:
        return False


load_dotenv()

ALLOWED_ZIP_CONTENT_TYPES = {
    "application/zip",
    "application/octet-stream",
    "application/x-zip-compressed",
}
DEFAULT_MAX_DEBUG_LOG_BYTES = 30 * 1024 * 1024


class DebugLogHttpError(Exception):
    def __init__(self, status_code: int, message: str, error_code: Optional[str] = None) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.message = message
        self.error_code = error_code


@dataclass(frozen=True)
class DebugLogConfig:
    bucket_name: str
    prefix: str
    project: Optional[str]
    max_debug_log_bytes: int


class DebugLogStore(Protocol):
    bucket_name: str

    def object_name_for(self, filename: Optional[str]) -> str: ...

    def write_object(self, object_name: str, data: bytes, content_type: str, metadata: Dict[str, str]) -> None: ...


class GcsDebugLogStore:
    def __init__(self, config: DebugLogConfig) -> None:
        from google.cloud import storage

        self.bucket_name = config.bucket_name
        self.prefix = config.prefix
        client = storage.Client(project=config.project) if config.project else storage.Client()
        self._bucket = client.bucket(config.bucket_name)

    def object_name_for(self, filename: Optional[str]) -> str:
        now = datetime.now(timezone.utc)
        date_path = now.strftime("%Y/%m/%d")
        timestamp = now.strftime("%Y%m%dT%H%M%SZ")
        name = safe_filename(filename or "droidlm-debug-logs.zip")
        object_id = secrets.token_hex(8)
        parts = [part for part in [self.prefix, date_path, f"{timestamp}-{object_id}-{name}"] if part]
        return "/".join(parts)

    def write_object(self, object_name: str, data: bytes, content_type: str, metadata: Dict[str, str]) -> None:
        blob = self._bucket.blob(require_debug_log_object_name(object_name, self.prefix))
        blob.metadata = metadata
        blob.upload_from_string(data, content_type=content_type)


_debug_log_store: Optional[GcsDebugLogStore] = None


def droidlm_debug_log_upload(request: Any):
    try:
        path = normalize_request_path(getattr(request, "path", "/"))
        if request.method == "GET":
            if path in {"/", "/health"}:
                return json_response({"ok": True})
            raise DebugLogHttpError(404, "Unknown path", "NOT_FOUND")
        if request.method != "POST":
            raise DebugLogHttpError(405, "Only POST debug log uploads are supported", "METHOD_NOT_ALLOWED")
        if path not in {"/", "/debug-logs"}:
            raise DebugLogHttpError(404, "Unknown path", "NOT_FOUND")

        logs = request.files.get("logs") if hasattr(request, "files") else None
        if logs is None:
            raise DebugLogHttpError(400, "Debug log upload is missing the logs file", "DEBUG_LOG_FILE_MISSING")

        config = debug_log_config()
        data = read_upload_data(logs, config.max_debug_log_bytes)
        payload = handle_debug_log_upload(
            filename=getattr(logs, "filename", None),
            content_type=getattr(logs, "content_type", None),
            data=data,
            app_package=form_value(request, "appPackage"),
            app_version=form_value(request, "appVersion"),
            store=require_debug_log_store(),
        )
        return json_response(payload)
    except DebugLogHttpError as error:
        return json_response(error_payload(error.message, error.error_code), status_code=error.status_code)
    except Exception as error:
        return json_response(
            error_payload(f"Could not upload debug logs: {error}", "GCS_WRITE_FAILED"),
            status_code=503,
        )


def handle_debug_log_upload(
    filename: Optional[str],
    content_type: Optional[str],
    data: bytes,
    app_package: Optional[str],
    app_version: Optional[str],
    store: DebugLogStore,
) -> Dict[str, Any]:
    normalized_content_type = (content_type or "application/zip").lower()
    if normalized_content_type not in ALLOWED_ZIP_CONTENT_TYPES:
        raise DebugLogHttpError(415, "Only zip debug log uploads are accepted", "DEBUG_LOG_CONTENT_TYPE_UNSUPPORTED")
    if not data:
        raise DebugLogHttpError(400, "Debug log upload is empty", "DEBUG_LOG_EMPTY")

    object_name = store.object_name_for(filename)
    metadata = {
        "source": "droidlm-android",
        "originalFilename": safe_filename(filename or "droidlm-debug-logs.zip"),
    }
    if app_package:
        metadata["appPackage"] = app_package[:128]
    if app_version:
        metadata["appVersion"] = app_version[:64]

    store.write_object(object_name, data, normalized_content_type, metadata)
    return {
        "ok": True,
        "bucket": store.bucket_name,
        "objectName": object_name,
        "gsUri": f"gs://{store.bucket_name}/{object_name}",
        "sizeBytes": len(data),
        "contentType": normalized_content_type,
    }


def debug_log_config() -> DebugLogConfig:
    bucket_name = os.getenv("DROIDLM_DEBUG_LOG_BUCKET", "droidlm-debug-logs").strip()
    if not bucket_name:
        raise DebugLogHttpError(503, "DroidLM debug log bucket is not configured", "DEBUG_LOG_BUCKET_MISSING")
    prefix = os.getenv("DROIDLM_DEBUG_LOG_PREFIX", "debug-logs").strip("/")
    max_debug_log_bytes = read_int_env("DROIDLM_MAX_DEBUG_LOG_BYTES", DEFAULT_MAX_DEBUG_LOG_BYTES)
    project = (
        os.getenv("DROIDLM_DEBUG_LOG_PROJECT")
        or os.getenv("GOOGLE_CLOUD_PROJECT")
        or os.getenv("GCLOUD_PROJECT")
        or os.getenv("GOOGLE_CLOUD_QUOTA_PROJECT")
    )
    return DebugLogConfig(
        bucket_name=bucket_name,
        prefix=prefix,
        project=project,
        max_debug_log_bytes=max_debug_log_bytes,
    )


def require_debug_log_store() -> GcsDebugLogStore:
    global _debug_log_store
    if _debug_log_store is None:
        _debug_log_store = GcsDebugLogStore(debug_log_config())
    return _debug_log_store


def read_upload_data(logs: Any, max_debug_log_bytes: int) -> bytes:
    stream = getattr(logs, "stream", logs)
    data = stream.read(max_debug_log_bytes + 1)
    if len(data) > max_debug_log_bytes:
        raise DebugLogHttpError(413, "Debug log upload is too large", "DEBUG_LOG_TOO_LARGE")
    return data


def normalize_request_path(path: Optional[str]) -> str:
    if not path:
        return "/"
    normalized = path.strip()
    if not normalized:
        return "/"
    if not normalized.startswith("/"):
        normalized = f"/{normalized}"
    return normalized.rstrip("/") or "/"


def require_debug_log_object_name(object_name: str, prefix: str) -> str:
    normalized = object_name.strip().lstrip("/")
    if not normalized or "//" in normalized or "/../" in f"/{normalized}/":
        raise DebugLogHttpError(400, "Invalid debug log object name", "DEBUG_LOG_OBJECT_INVALID")
    if prefix and not normalized.startswith(f"{prefix}/"):
        raise DebugLogHttpError(403, "Debug log object is outside the configured prefix", "DEBUG_LOG_OBJECT_FORBIDDEN")
    return normalized


def safe_filename(filename: str) -> str:
    name = Path(filename).name
    safe = re.sub(r"[^A-Za-z0-9._-]+", "-", name).strip(".-")
    if not safe:
        safe = "droidlm-debug-logs.zip"
    if not safe.lower().endswith(".zip"):
        safe = f"{safe}.zip"
    return safe[:120]


def form_value(request: Any, key: str) -> Optional[str]:
    value = request.form.get(key) if hasattr(request, "form") else None
    if value is None:
        return None
    trimmed = value.strip()
    return trimmed or None


def read_int_env(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    try:
        value = int(raw)
    except ValueError as error:
        raise DebugLogHttpError(500, f"Environment variable {name} must be an integer", "ENV_INVALID") from error
    if value <= 0:
        raise DebugLogHttpError(500, f"Environment variable {name} must be positive", "ENV_INVALID")
    return value


def error_payload(message: str, error_code: Optional[str] = None) -> Dict[str, Any]:
    detail: Dict[str, Any] = {"message": message}
    payload: Dict[str, Any] = {"detail": detail, "message": message}
    if error_code:
        detail["errorCode"] = error_code
        payload["errorCode"] = error_code
    return payload


def json_response(payload: Dict[str, Any], status_code: int = 200):
    return json.dumps(payload), status_code, {"Content-Type": "application/json"}
