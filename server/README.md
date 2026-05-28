# DroidLM Relay

FastAPI relay for DroidLM. It keeps the OpenAI API key on the server and exposes only DroidLM-specific endpoints to the Android app.

## Run locally

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
# edit .env and set OPENAI_API_KEY
uvicorn main:app --host 0.0.0.0 --port 8787
```

Defaults use OpenAI's current speech-to-text model `gpt-4o-transcribe`, plus GPT-5.x models for optional planning and vision relay calls. Override `OPENAI_TRANSCRIBE_MODEL`, `OPENAI_PLANNER_MODEL`, or `OPENAI_VISION_MODEL` in `.env` if needed.

## Debug log uploads

Set `DROIDLM_DEBUG_LOG_BUCKET=droidlm-debug-logs` and `DROIDLM_DEBUG_LOG_PROJECT=droidlm-495821` on the relay, then run the relay on Google Cloud with a service account that has bucket-scoped `storage.objects.create`. Signed direct-to-GCS upload sessions also require the service account to be able to sign URLs, for example by granting it `roles/iam.serviceAccountTokenCreator` on itself when using Cloud Functions default credentials. Add `storage.objects.get` only if you want the relay's admin read endpoint to work. Locally, use Application Default Credentials or `GOOGLE_APPLICATION_CREDENTIALS` pointing to a credential file outside this repository. The Android app asks the relay for an authenticated upload session, then uploads zipped debug logs to the returned signed GCS URL; admin reads are available from `GET /debug-logs/{objectName}` with the relay setup token.

A Cloud Functions 2nd gen version of the upload endpoint lives in `server/gcf_debug_logs/`. Deploy it with `scripts/deploy-debug-log-upload-function.sh`. Android debug-log upload uses the hidden `BuildConfig.DEBUG_LOG_UPLOAD_URL` endpoint, which defaults to the hosted function root; override it at build time with `DROIDLM_DEBUG_LOG_UPLOAD_URL` or `-Pdroidlm.debugLogUploadUrl=...` for development builds. The legacy proxied `POST /debug-logs` path remains as a compatibility fallback.

## Allowlist enforcement

Cloud-backed DroidLM endpoints require a Firebase ID token for a verified email that exists in the encrypted Google Cloud allowlist. The allowlist stores HMAC document IDs in Firestore and encrypted metadata via Cloud KMS; keep allowlist entries, HMAC keys, and KMS material in Google Cloud only. Configure deployments with `DROIDLM_FIREBASE_PROJECT_ID`, `DROIDLM_ALLOWLIST_PROJECT`, `DROIDLM_ALLOWLIST_COLLECTION`, and `DROIDLM_ALLOWLIST_HMAC_SECRET`.


## Endpoints

- `GET /health` returns `{ "ok": true }`.
- `POST /allowlist/check` verifies the Firebase token and reports allowlist access.
- `POST /transcribe` accepts an audio multipart field named `audio` and returns `{ "text": "..." }`.
- `POST /plan-action` returns exactly one DroidLM JSON action object.
- `POST /analyze-screenshot` accepts a PNG/JPEG/WebP screenshot and returns OCR/vision JSON.
- `POST /debug-logs` accepts a zipped debug log field named `logs` and stores it in GCS.
- `GET /debug-logs/{objectName}` downloads a stored debug log when called with the relay setup token.

The relay does not execute Android actions. It only calls OpenAI and returns structured results to the Android app.
