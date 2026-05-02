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

## Endpoints

- `GET /health` returns `{ "ok": true }`.
- `POST /transcribe` accepts an audio multipart field named `audio` and returns `{ "text": "..." }`.
- `POST /plan-action` returns exactly one DroidLM JSON action object.
- `POST /analyze-screenshot` accepts a PNG/JPEG/WebP screenshot and returns OCR/vision JSON.

The relay does not execute Android actions. It only calls OpenAI and returns structured results to the Android app.
