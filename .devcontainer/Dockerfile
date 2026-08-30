# EasyOTP Android build environment.
#
# Tuned for a 16GB host under memory pressure: no emulator, no SDK components we
# do not build against. The SDK lives in the image (reproducible, matches CI and
# purges with `docker rmi`); Gradle caches live in a named volume so they survive
# image rebuilds but can be dropped independently.

FROM eclipse-temurin:17-jdk-noble

# Resolved at build time by ./dev from Google's repository manifest, because
# these filenames carry a build number and rot without warning (the 2024
# 11076708 build is already a 404). The default below is only a fallback.
ARG CMDLINE_TOOLS_URL=https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
ARG ANDROID_API=35
ARG BUILD_TOOLS=35.0.0

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/home/dev/.gradle \
    DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
        unzip curl git ca-certificates openssh-client android-sdk-platform-tools-common \
    && rm -rf /var/lib/apt/lists/*

RUN set -eux; \
    mkdir -p "$ANDROID_HOME/cmdline-tools"; \
    curl -fsSL -o /tmp/cmdline-tools.zip "$CMDLINE_TOOLS_URL"; \
    unzip -q /tmp/cmdline-tools.zip -d "$ANDROID_HOME/cmdline-tools"; \
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"; \
    rm /tmp/cmdline-tools.zip

ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

RUN set -eux; \
    yes | sdkmanager --licenses > /dev/null; \
    sdkmanager --install \
        "platform-tools" \
        "platforms;android-${ANDROID_API}" \
        "build-tools;${BUILD_TOOLS}" > /dev/null; \
    rm -rf "$ANDROID_HOME/.temp" /root/.android

# Match the host uid/gid so bind-mounted sources do not end up root-owned.
ARG UID=1000
ARG GID=1000
RUN groupadd -g "$GID" dev 2>/dev/null || true; \
    useradd -m -u "$UID" -g "$GID" -s /bin/bash dev 2>/dev/null || true; \
    mkdir -p /home/dev/.gradle && chown -R "$UID:$GID" /home/dev; \
    chown -R "$UID:$GID" "$ANDROID_HOME"

USER dev
WORKDIR /workspace
