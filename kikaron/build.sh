#!/usr/bin/env bash
# Build and sign Kikaron Multipass for Android (the fdroid flavour: no Google Play
# services, no Firebase). Needs: JDK 21 (~/.local/share/jdk/jdk-21*), the Android
# SDK (~/Android/Sdk), a GitHub token with read:packages (gh auth token) for the
# Bitwarden SDK package, and the release key in ~/.config/kikaron-multipass/
# (multipass-release.jks + keystore.pass - back them up; a lost key means a new
# app identity). Writes dist/kikaron-multipass-<version>.apk.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="$(ls -d ~/.local/share/jdk/jdk-21* | tail -1)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export GITHUB_TOKEN="${GITHUB_TOKEN:-$(gh auth token)}"
KEYDIR="$HOME/.config/kikaron-multipass"
export MULTIPASS_KEYSTORE="$KEYDIR/multipass-release.jks"
export MULTIPASS_KEYSTORE_PASSWORD="$(cat "$KEYDIR/keystore.pass")"
export MULTIPASS_KEY_ALIAS=multipass
export MULTIPASS_KEY_PASSWORD="$MULTIPASS_KEYSTORE_PASSWORD"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew --no-daemon :app:assembleFdroidRelease
apk=app/build/outputs/apk/fdroid/release/com.kikaron.multipass-fdroid.apk
version="$(grep -m1 '^appVersionName' gradle/libs.versions.toml | cut -d'"' -f2)"
mkdir -p ../dist
cp "$apk" "../dist/kikaron-multipass-$version.apk"
echo "built ../dist/kikaron-multipass-$version.apk"
