#!/usr/bin/env bash
set -euo pipefail

FUNCTION_NAME="${DROIDLM_DEBUG_LOG_FUNCTION_NAME:-droidlm-debug-log-upload}"
PROJECT="${DROIDLM_GCP_PROJECT:-${GOOGLE_CLOUD_PROJECT:-}}"
REGION="${DROIDLM_GCP_REGION:-}"
SERVICE_ACCOUNT="${DROIDLM_DEBUG_LOG_SERVICE_ACCOUNT:-}"
BUCKET="${DROIDLM_DEBUG_LOG_BUCKET:-droidlm-debug-logs}"
PREFIX="${DROIDLM_DEBUG_LOG_PREFIX:-debug-logs}"
MAX_BYTES="${DROIDLM_MAX_DEBUG_LOG_BYTES:-31457280}"
ALLOW_UNAUTHENTICATED="${DROIDLM_DEBUG_LOG_FUNCTION_ALLOW_UNAUTHENTICATED:-true}"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

usage() {
  printf 'Deploy DroidLM debug-log upload Cloud Function (2nd gen).\n'
  printf 'Required env: DROIDLM_GCP_REGION, DROIDLM_DEBUG_LOG_SERVICE_ACCOUNT, and DROIDLM_GCP_PROJECT or GOOGLE_CLOUD_PROJECT.\n'
  printf 'Optional env: DROIDLM_DEBUG_LOG_FUNCTION_NAME, DROIDLM_DEBUG_LOG_BUCKET, DROIDLM_DEBUG_LOG_PREFIX, DROIDLM_MAX_DEBUG_LOG_BYTES.\n'
}

[[ -n "$PROJECT" ]] || { usage; fail "Set DROIDLM_GCP_PROJECT or GOOGLE_CLOUD_PROJECT"; }
[[ -n "$REGION" ]] || { usage; fail "Set DROIDLM_GCP_REGION"; }
[[ -n "$SERVICE_ACCOUNT" ]] || { usage; fail "Set DROIDLM_DEBUG_LOG_SERVICE_ACCOUNT"; }
[[ "$MAX_BYTES" =~ ^[0-9]+$ ]] || fail "DROIDLM_MAX_DEBUG_LOG_BYTES must be numeric"
[[ "$ALLOW_UNAUTHENTICATED" == "true" || "$ALLOW_UNAUTHENTICATED" == "false" ]] || fail "DROIDLM_DEBUG_LOG_FUNCTION_ALLOW_UNAUTHENTICATED must be true or false"

ENV_VARS="DROIDLM_DEBUG_LOG_BUCKET=${BUCKET},DROIDLM_DEBUG_LOG_PREFIX=${PREFIX},DROIDLM_MAX_DEBUG_LOG_BYTES=${MAX_BYTES},DROIDLM_DEBUG_LOG_PROJECT=${PROJECT}"
CMD=(
  gcloud functions deploy "$FUNCTION_NAME"
  --project "$PROJECT"
  --gen2
  --runtime python312
  --region "$REGION"
  --source "server/gcf_debug_logs"
  --entry-point "droidlm_debug_log_upload"
  --trigger-http
  --service-account "$SERVICE_ACCOUNT"
  --memory "512MiB"
  --timeout "60s"
  --set-env-vars "$ENV_VARS"
)

if [[ "$ALLOW_UNAUTHENTICATED" == "true" ]]; then
  CMD+=(--allow-unauthenticated)
fi

"${CMD[@]}"
