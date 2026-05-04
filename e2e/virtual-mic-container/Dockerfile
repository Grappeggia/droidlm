FROM ubuntu:24.04

ARG DEBIAN_FRONTEND=noninteractive
ARG UID=1000
ARG GID=1000
ARG ANDROID_CMDLINE_TOOLS_VERSION=11076708
ARG ANDROID_PLATFORM=android-34
ARG ANDROID_BUILD_TOOLS=35.0.0
ARG ANDROID_SYSTEM_IMAGE=system-images;android-34;google_apis;x86_64

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
    PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/android-sdk/emulator:/usr/lib/jvm/java-17-openjdk-amd64/bin:$PATH

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    ffmpeg \
    git \
    libasound2t64 \
    libc6 \
    libdbus-1-3 \
    libfontconfig1 \
    libgcc-s1 \
    libgl1 \
    libnss3 \
    libpulse0 \
    libx11-6 \
    libxcb1 \
    libxcomposite1 \
    libxcursor1 \
    libxdamage1 \
    libxext6 \
    libxi6 \
    libxrandr2 \
    libxtst6 \
    openjdk-17-jdk-headless \
    pulseaudio \
    pulseaudio-utils \
    python3 \
    python3-grpcio \
    python3-grpc-tools \
    python3-protobuf \
    python3-pkg-resources \
    unzip \
    && rm -rf /var/lib/apt/lists/*

RUN if getent group "$GID" >/dev/null; then group_name="$(getent group "$GID" | cut -d: -f1)"; else groupadd --gid "$GID" droidlm && group_name=droidlm; fi \
    && if getent passwd "$UID" >/dev/null; then existing_user="$(getent passwd "$UID" | cut -d: -f1)" && usermod --login droidlm --home /home/droidlm --move-home "$existing_user" && usermod --gid "$GID" droidlm; else useradd --uid "$UID" --gid "$GID" --create-home --home-dir /home/droidlm --shell /bin/bash droidlm; fi \
    && mkdir -p "$ANDROID_HOME/cmdline-tools" \
    && curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip" -o /tmp/cmdline-tools.zip \
    && unzip -q /tmp/cmdline-tools.zip -d /tmp/android-cmdline-tools \
    && mv /tmp/android-cmdline-tools/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest" \
    && rm -rf /tmp/cmdline-tools.zip /tmp/android-cmdline-tools \
    && yes | sdkmanager --licenses >/dev/null \
    && sdkmanager \
        "platform-tools" \
        "emulator" \
        "platforms;${ANDROID_PLATFORM}" \
        "build-tools;${ANDROID_BUILD_TOOLS}" \
        "${ANDROID_SYSTEM_IMAGE}" \
    && chown -R droidlm:"$GID" "$ANDROID_HOME" /home/droidlm

USER droidlm
WORKDIR /workspace

ENV DROIDLM_E2E_SYSTEM_IMAGE=$ANDROID_SYSTEM_IMAGE \
    DROIDLM_E2E_AVD=droidlm_e2e \
    GRADLE_USER_HOME=/home/droidlm/.gradle

ENTRYPOINT ["bash", "scripts/containerized-virtual-mic-e2e.sh"]
CMD []
