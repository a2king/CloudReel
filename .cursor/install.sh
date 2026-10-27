#!/usr/bin/env bash
# Idempotent Cloud Agent bootstrap: JDK 17 and Android SDK 35.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

JAVA_HOME_17=/usr/lib/jvm/java-17-openjdk-amd64
SDK=/opt/android-sdk

if [ ! -x "$JAVA_HOME_17/bin/java" ]; then
  sudo apt-get update
  sudo apt-get install -y openjdk-17-jdk-headless unzip curl ca-certificates
fi

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  tmp=$(mktemp -d)
  curl -fL --retry 3 -o "$tmp/cmdtools.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
  unzip -q "$tmp/cmdtools.zip" -d "$tmp"
  sudo mkdir -p "$SDK/cmdline-tools/latest"
  sudo cp -a "$tmp/cmdline-tools/." "$SDK/cmdline-tools/latest/"
  sudo chown -R "$(id -u)":"$(id -g)" "$SDK"
  rm -rf "$tmp"
fi

export JAVA_HOME="$JAVA_HOME_17"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="$JAVA_HOME/bin:$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$PATH"

if [ ! -d "$SDK/platforms/android-35" ] || [ ! -d "$SDK/build-tools/35.0.0" ]; then
  yes | sdkmanager --sdk_root="$SDK" --licenses >/dev/null || true
  sdkmanager --sdk_root="$SDK" "platforms;android-35" "build-tools;35.0.0" "platform-tools"
fi

sudo tee /etc/profile.d/cloudreel-android.sh >/dev/null <<'EOF'
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
EOF

echo "jdk=$(java -version 2>&1 | head -n 1)"
echo "sdk=$ANDROID_HOME"
