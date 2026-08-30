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
# Several platforms, not one. sdkmanager cannot fetch packages on a workstation
# where dl.google.com is blocked (DECISIONS D10), so an SDK component that turns
# out to be missing costs a CI rebuild plus a multi-GB pull. AndroidX moves its
# compileSdk floor forward regularly -- Compose 1.12 and core-ktx 1.19 already
# require 37 -- so the image carries a range and picks the newest build-tools.
ARG ANDROID_APIS="35 36 37"

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

# Gradle itself, so bootstrapping the wrapper does not require a 130MB download
# on a constrained link. Once gradlew is committed the wrapper manages its own
# distribution, but a fresh clone still needs something to generate it with.
ARG GRADLE_VERSION=9.7.1
RUN set -eux; \
    curl -fsSL -o /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"; \
    unzip -q /tmp/gradle.zip -d /opt; \
    ln -s "/opt/gradle-${GRADLE_VERSION}/bin/gradle" /usr/local/bin/gradle; \
    rm /tmp/gradle.zip; \
    gradle --version

RUN set -eux; \
    yes | sdkmanager --licenses > /dev/null; \
    pkgs="platform-tools"; \
    for api in $ANDROID_APIS; do pkgs="$pkgs platforms;android-$api"; done; \
    # Newest build-tools available, since AGP pins a minimum that tracks the
    # newest platform and refuses an override to an earlier revision.
    latest_bt=$(sdkmanager --list 2>/dev/null \
        | grep -oE 'build-tools;[0-9]+\.[0-9]+\.[0-9]+' | sort -uV | tail -1); \
    test -n "$latest_bt"; \
    # Only the newest, plus revisions confirmed to exist. Guessing a name like
    # "build-tools;37.0.0" would fail the whole image build if it is not published.
    pkgs="$pkgs $latest_bt build-tools;36.0.0 build-tools;35.0.0"; \
    echo "installing: $pkgs"; \
    sdkmanager --install $pkgs > /dev/null; \
    rm -rf "$ANDROID_HOME/.temp" /root/.android

# Match the host uid/gid so bind-mounted sources do not end up root-owned.
#
# Ubuntu 24.04 (noble) bases ship an `ubuntu` account already holding uid/gid
# 1000, which is the usual host uid. The collision has to be resolved explicitly:
# an earlier version suppressed the useradd failure with `|| true`, which built a
# perfectly good image with no `dev` user in it and failed only at `docker run`,
# on someone else's machine, after a multi-GB pull. No error suppression here.
ARG UID=1000
ARG GID=1000
RUN set -eux; \
    if existing=$(getent passwd "$UID" | cut -d: -f1) && [ -n "$existing" ]; then \
        userdel -r "$existing" 2>/dev/null || userdel "$existing"; \
    fi; \
    if existinggrp=$(getent group "$GID" | cut -d: -f1) && [ -n "$existinggrp" ]; then \
        groupdel "$existinggrp" 2>/dev/null || true; \
    fi; \
    groupadd -g "$GID" dev; \
    useradd -m -u "$UID" -g "$GID" -s /bin/bash dev; \
    mkdir -p /home/dev/.gradle; \
    chown -R "$UID:$GID" /home/dev "$ANDROID_HOME"; \
    # Prove the account resolves, so a broken image fails the build, not the user.
    id dev

USER dev
WORKDIR /workspace
