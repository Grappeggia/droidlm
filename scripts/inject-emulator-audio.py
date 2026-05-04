#!/usr/bin/env python3
import argparse
import os
import sys
import time
import wave
from pathlib import Path
from typing import NoReturn


def fail(message) -> NoReturn:
    print(f"ERROR: {message}", file=sys.stderr)
    sys.exit(1)


def ensure_grpc_modules(proto_path, output_dir):
    try:
        import grpc  # noqa: F401
        from grpc_tools import protoc  # noqa: F401
    except ImportError as exc:
        fail("Python packages grpcio and grpcio-tools are required for emulator audio injection")

    output_dir.mkdir(parents=True, exist_ok=True)
    from grpc_tools import protoc
    import grpc_tools

    grpc_tools_file = getattr(grpc_tools, "__file__", None)
    if grpc_tools_file is None:
        fail("Could not locate grpc_tools package files")
    grpc_tools_proto = Path(grpc_tools_file).parent / "_proto"
    args = [
        "grpc_tools.protoc",
        f"-I{proto_path.parent}",
        f"-I{grpc_tools_proto}",
        f"--python_out={output_dir}",
        f"--grpc_python_out={output_dir}",
        str(proto_path),
    ]
    result = protoc.main(args)
    if result != 0:
        fail(f"protoc failed with exit code {result}")

    sys.path.insert(0, str(output_dir))
    import grpc
    import emulator_controller_pb2
    import emulator_controller_pb2_grpc
    return grpc, emulator_controller_pb2, emulator_controller_pb2_grpc

def grpc_metadata(token):
    return (("authorization", f"Bearer {token}"),) if token else None


def create_grpc_channel(grpc, target, deadline_seconds, insecure):
    if insecure:
        channel = grpc.insecure_channel(target)
    else:
        if not hasattr(grpc, "local_channel_credentials"):
            fail("grpc.local_channel_credentials is unavailable; install a newer grpcio or pass --insecure")
        credentials = grpc.local_channel_credentials(grpc.LocalConnectionType.LOCAL_TCP)
        channel = grpc.secure_channel(target, credentials)
    try:
        grpc.channel_ready_future(channel).result(timeout=deadline_seconds)
    except grpc.FutureTimeoutError:
        fail(f"emulator gRPC endpoint did not become ready at {target}")
    return channel

def discover_grpc_token(port):
    runtime_dir = os.environ.get("XDG_RUNTIME_DIR")
    if not runtime_dir:
        return None
    running_dir = Path(runtime_dir) / "avd" / "running"
    if not running_dir.exists():
        return None
    ini_files = sorted(running_dir.glob("pid_*_info.ini"), key=lambda path: path.stat().st_mtime, reverse=True)
    for ini_path in ini_files:
        values = {}
        for line in ini_path.read_text(errors="ignore").splitlines():
            if "=" not in line:
                continue
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
        if values.get("grpc.port") == str(port) and values.get("grpc.token"):
            return values["grpc.token"]
    return None


def wav_chunks(wav_path, chunk_ms):
    with wave.open(str(wav_path), "rb") as wav_file:
        channels = wav_file.getnchannels()
        sample_width = wav_file.getsampwidth()
        sample_rate = wav_file.getframerate()
        if channels not in (1, 2):
            fail(f"unsupported channel count {channels}; expected mono or stereo")
        if sample_width not in (1, 2):
            fail(f"unsupported sample width {sample_width}; expected 8-bit or 16-bit PCM")
        if sample_rate > 48000:
            fail(f"unsupported sample rate {sample_rate}; emulator injection supports <= 48000 Hz")

        frames_per_chunk = max(1, int(sample_rate * chunk_ms / 1000))
        while True:
            data = wav_file.readframes(frames_per_chunk)
            if not data:
                break
            duration_s = (len(data) / (sample_width * channels)) / sample_rate
            yield data, duration_s, channels, sample_width, sample_rate


def packet_stream(pb2, wav_path, chunk_ms, realtime):
    first = True
    started_at = time.monotonic()
    audio_time = 0.0
    for data, duration_s, channels, sample_width, sample_rate in wav_chunks(wav_path, chunk_ms):
        packet = pb2.AudioPacket(audio=data)
        if first:
            packet.format.samplingRate = sample_rate
            packet.format.channels = pb2.AudioFormat.Mono if channels == 1 else pb2.AudioFormat.Stereo
            packet.format.format = pb2.AudioFormat.AUD_FMT_U8 if sample_width == 1 else pb2.AudioFormat.AUD_FMT_S16
            packet.format.mode = pb2.AudioFormat.MODE_REAL_TIME if realtime else pb2.AudioFormat.MODE_UNSPECIFIED
            first = False
        yield packet
        audio_time += duration_s
        if realtime:
            target = started_at + audio_time
            delay = target - time.monotonic()
            if delay > 0:
                time.sleep(delay)


def main():
    parser = argparse.ArgumentParser(description="Inject a PCM WAV into Android Emulator microphone input via gRPC.")
    parser.add_argument("--wav", required=True, type=Path, help="PCM WAV file to inject")
    parser.add_argument("--host", default=os.environ.get("DROIDLM_E2E_GRPC_HOST", "127.0.0.1"))
    parser.add_argument("--port", default=os.environ.get("DROIDLM_E2E_GRPC_PORT", "8554"))
    parser.add_argument("--proto", type=Path, default=None, help="Path to emulator_controller.proto")
    parser.add_argument("--generated-dir", type=Path, default=Path("build/emulator-grpc-python"))
    parser.add_argument("--chunk-ms", type=int, default=20)
    parser.add_argument("--deadline-seconds", type=float, default=30.0)
    parser.add_argument("--no-realtime", action="store_true", help="Do not pace packets in real time")
    parser.add_argument("--token", default=os.environ.get("DROIDLM_E2E_GRPC_TOKEN"), help="Bearer token for emulator gRPC, if required")
    parser.add_argument("--insecure", action="store_true", help="Use an insecure gRPC channel instead of emulator local credentials")
    parser.add_argument("--skip-smoke-check", action="store_true", help="Skip getStatus smoke check before injectAudio")
    parser.add_argument("--status-only", action="store_true", help="Only run the getStatus smoke check")
    args = parser.parse_args()

    if not args.wav.exists():
        fail(f"WAV file does not exist: {args.wav}")

    android_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    proto_path = args.proto
    if proto_path is None:
        if not android_home:
            fail("ANDROID_HOME or ANDROID_SDK_ROOT is required when --proto is not set")
        proto_path = Path(str(android_home)) / "emulator" / "lib" / "emulator_controller.proto"
    if not proto_path.exists():
        fail(f"emulator_controller.proto not found: {proto_path}")

    grpc, pb2, pb2_grpc = ensure_grpc_modules(proto_path, args.generated_dir)
    from google.protobuf import empty_pb2

    target = f"{args.host}:{args.port}"
    token = args.token or discover_grpc_token(args.port)
    metadata = grpc_metadata(token)
    channel = create_grpc_channel(grpc, target, args.deadline_seconds, args.insecure)
    stub = pb2_grpc.EmulatorControllerStub(channel)
    if not args.skip_smoke_check:
        try:
            stub.getStatus(empty_pb2.Empty(), timeout=args.deadline_seconds, metadata=metadata)
        except grpc.RpcError as exc:
            fail(f"emulator gRPC getStatus failed: {exc.code().name}: {exc.details()}")
    if args.status_only:
        return
    try:
        stub.injectAudio(
            packet_stream(pb2, args.wav, args.chunk_ms, not args.no_realtime),
            timeout=args.deadline_seconds,
            metadata=metadata,
        )
    except grpc.RpcError as exc:
        fail(f"emulator gRPC injectAudio failed: {exc.code().name}: {exc.details()}")


if __name__ == "__main__":
    main()
