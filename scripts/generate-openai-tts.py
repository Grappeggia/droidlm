#!/usr/bin/env python3
import argparse
import json
import os
import pathlib
import sys
import urllib.error
import urllib.request


def load_env_key(repo_dir: pathlib.Path) -> str | None:
    env_file = repo_dir / ".env.local"
    if not env_file.is_file():
        return None
    for raw in env_file.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", 1)
        if name.strip() == "OPENAI_API_KEY" and value.strip():
            return value.strip().strip('"').strip("'")
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate deterministic OpenAI TTS audio for DroidLM E2E tests.")
    parser.add_argument("--text", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--model", default="gpt-4o-mini-tts")
    parser.add_argument("--voice", default="alloy")
    parser.add_argument("--format", default="wav", choices=["wav", "mp3", "opus", "aac", "flac", "pcm"])
    args = parser.parse_args()

    repo_dir = pathlib.Path(__file__).resolve().parents[1]
    output = pathlib.Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.is_file() and output.stat().st_size > 0:
        print(f"Using cached TTS audio: {output}")
        return 0

    api_key = os.environ.get("OPENAI_API_KEY") or load_env_key(repo_dir)
    if not api_key:
        print("OPENAI_API_KEY is missing from environment and .env.local", file=sys.stderr)
        return 2

    body = json.dumps({
        "model": args.model,
        "voice": args.voice,
        "input": args.text,
        "response_format": args.format,
    }).encode("utf-8")
    request = urllib.request.Request(
        "https://api.openai.com/v1/audio/speech",
        data=body,
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            output.write_bytes(response.read())
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        print(f"OpenAI TTS failed with HTTP {exc.code}: {detail}", file=sys.stderr)
        return 3
    except Exception as exc:
        print(f"OpenAI TTS failed: {exc}", file=sys.stderr)
        return 4

    print(f"Generated TTS audio: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
