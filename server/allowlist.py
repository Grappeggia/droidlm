import hashlib
import hmac
import os
from dataclasses import dataclass
from typing import Any, Dict, Optional


class AllowlistError(Exception):
    def __init__(self, status_code: int, message: str, error_code: str) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.message = message
        self.error_code = error_code


@dataclass(frozen=True)
class AllowlistedUser:
    uid: str
    email: str


def normalize_email(email: str) -> str:
    return email.strip().lower()


def allowlist_doc_id(email: str, hmac_key: bytes) -> str:
    normalized = normalize_email(email)
    if not normalized:
        raise AllowlistError(401, "Firebase token does not include an email", "AUTH_EMAIL_MISSING")
    return hmac.new(hmac_key, normalized.encode("utf-8"), hashlib.sha256).hexdigest()


def bearer_token(authorization: Optional[str]) -> str:
    value = (authorization or "").strip()
    if not value:
        raise AllowlistError(401, "Missing Firebase ID token", "AUTH_TOKEN_MISSING")
    scheme, _, token = value.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise AllowlistError(401, "Authorization must be a Bearer Firebase ID token", "AUTH_TOKEN_INVALID")
    return token.strip()


class GcpAllowlistVerifier:
    def __init__(self) -> None:
        self.project = (
            os.getenv("DROIDLM_ALLOWLIST_PROJECT")
            or os.getenv("GOOGLE_CLOUD_PROJECT")
            or os.getenv("GCLOUD_PROJECT")
            or os.getenv("GOOGLE_CLOUD_QUOTA_PROJECT")
        )
        self.firebase_project = os.getenv("DROIDLM_FIREBASE_PROJECT_ID") or self.project
        self.collection = os.getenv("DROIDLM_ALLOWLIST_COLLECTION", "allowlist_entries")
        self.hmac_secret = os.getenv("DROIDLM_ALLOWLIST_HMAC_SECRET", "droidlm-allowlist-hmac-key")
        self._hmac_key: Optional[bytes] = None
        self._firestore_client: Any = None
        self._firebase_app: Any = None

    def require_allowlisted(self, authorization: Optional[str]) -> AllowlistedUser:
        decoded = self._verify_token(bearer_token(authorization))
        email = normalize_email(str(decoded.get("email") or ""))
        uid = str(decoded.get("uid") or decoded.get("sub") or "")
        if not email:
            raise AllowlistError(401, "Firebase token does not include an email", "AUTH_EMAIL_MISSING")
        if not decoded.get("email_verified", False):
            raise AllowlistError(403, "Verify your email address before using DroidLM", "AUTH_EMAIL_UNVERIFIED")
        if not self._is_email_allowed(email):
            raise AllowlistError(403, "This account is not on the DroidLM allowlist", "AUTH_NOT_ALLOWLISTED")
        return AllowlistedUser(uid=uid, email=email)

    def _verify_token(self, token: str) -> Dict[str, Any]:
        if not self.firebase_project:
            raise AllowlistError(503, "Firebase project is not configured", "ALLOWLIST_CONFIG_MISSING")
        try:
            from firebase_admin import auth, credentials, initialize_app, get_app
        except Exception as error:  # pragma: no cover - deployment dependency
            raise AllowlistError(503, f"Firebase Admin SDK is not available: {error}", "ALLOWLIST_DEPENDENCY_MISSING") from error

        if self._firebase_app is None:
            try:
                self._firebase_app = get_app("droidlm-allowlist")
            except ValueError:
                self._firebase_app = initialize_app(
                    credentials.ApplicationDefault(),
                    {"projectId": self.firebase_project},
                    name="droidlm-allowlist",
                )
        try:
            return auth.verify_id_token(token, app=self._firebase_app, check_revoked=False)
        except Exception as error:
            raise AllowlistError(401, "Firebase ID token could not be verified", "AUTH_TOKEN_INVALID") from error

    def _is_email_allowed(self, email: str) -> bool:
        document_id = allowlist_doc_id(email, self._require_hmac_key())
        try:
            snapshot = self._firestore().collection(self.collection).document(document_id).get()
        except Exception as error:
            raise AllowlistError(503, f"Could not read allowlist: {error}", "ALLOWLIST_READ_FAILED") from error
        if not snapshot.exists:
            return False
        data = snapshot.to_dict() or {}
        return bool(data.get("enabled", True))

    def _require_hmac_key(self) -> bytes:
        if self._hmac_key is not None:
            return self._hmac_key
        if not self.project:
            raise AllowlistError(503, "Allowlist project is not configured", "ALLOWLIST_CONFIG_MISSING")
        try:
            from google.cloud import secretmanager
        except Exception as error:  # pragma: no cover - deployment dependency
            raise AllowlistError(503, f"Secret Manager SDK is not available: {error}", "ALLOWLIST_DEPENDENCY_MISSING") from error
        name = self.hmac_secret
        if not name.startswith("projects/"):
            name = f"projects/{self.project}/secrets/{name}/versions/latest"
        elif "/versions/" not in name:
            name = f"{name}/versions/latest"
        try:
            response = secretmanager.SecretManagerServiceClient().access_secret_version(request={"name": name})
        except Exception as error:
            raise AllowlistError(503, f"Could not read allowlist secret: {error}", "ALLOWLIST_SECRET_READ_FAILED") from error
        key = response.payload.data
        if not key:
            raise AllowlistError(503, "Allowlist HMAC secret is empty", "ALLOWLIST_CONFIG_MISSING")
        self._hmac_key = key
        return key

    def _firestore(self) -> Any:
        if self._firestore_client is not None:
            return self._firestore_client
        if not self.project:
            raise AllowlistError(503, "Allowlist project is not configured", "ALLOWLIST_CONFIG_MISSING")
        try:
            from google.cloud import firestore
        except Exception as error:  # pragma: no cover - deployment dependency
            raise AllowlistError(503, f"Firestore SDK is not available: {error}", "ALLOWLIST_DEPENDENCY_MISSING") from error
        self._firestore_client = firestore.Client(project=self.project)
        return self._firestore_client


_allowlist_verifier: Optional[GcpAllowlistVerifier] = None


def require_allowlisted_user(authorization: Optional[str]) -> AllowlistedUser:
    global _allowlist_verifier
    if _allowlist_verifier is None:
        _allowlist_verifier = GcpAllowlistVerifier()
    return _allowlist_verifier.require_allowlisted(authorization)


def allowlist_error_payload(error: AllowlistError) -> Dict[str, Any]:
    return {
        "detail": {
            "message": error.message,
            "errorCode": error.error_code,
        },
        "message": error.message,
        "errorCode": error.error_code,
    }
