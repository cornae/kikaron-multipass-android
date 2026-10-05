#!/usr/bin/env bash
# Build and sign Kikaron Multipass for Android (the fdroid flavour: no Google Play
# services, no Firebase). Needs: JDK 21 (~/.local/share/jdk/jdk-21*), the Android
# SDK (~/Android/Sdk), a GitHub token with read:packages (gh auth token) for the
# Bitwarden SDK package, and the Kikaron upload key in ~/keystores/. Writes
# ../dist/kikaron-multipass-<version>-<code>.apk and ../dist/com.kikaron.multipass.aab.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="$(ls -d ~/.local/share/jdk/jdk-21* | tail -1)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export GITHUB_TOKEN="${GITHUB_TOKEN:-$(gh auth token)}"
# the one key every Kikaron Android app is signed with (Play reuses it per app, so
# a side-loaded copy and the Play copy update each other) - see
# kikaron-app-android/play-store/INTERNAL-TESTING.md
. ~/keystores/kikaron-documents-upload.env
export MULTIPASS_KEYSTORE="$OC_RELEASE_KEYSTORE"
export MULTIPASS_KEYSTORE_PASSWORD="$OC_RELEASE_KEYSTORE_PASSWORD"
export MULTIPASS_KEY_ALIAS="$OC_RELEASE_KEY_ALIAS"
export MULTIPASS_KEY_PASSWORD="$OC_RELEASE_KEY_PASSWORD"
# yyMMddHH: rises with every build, fits Android's version code
export MULTIPASS_VERSION_CODE="${MULTIPASS_VERSION_CODE:-$(date -u +%y%m%d%H)}"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew --no-daemon :app:assembleFdroidRelease :app:bundleFdroidRelease
apk=app/build/outputs/apk/fdroid/release/com.kikaron.multipass-fdroid.apk
version="$(grep -m1 '^appVersionName' gradle/libs.versions.toml | cut -d'"' -f2)"
mkdir -p ../dist
cp "$apk" "../dist/kikaron-multipass-$version-$MULTIPASS_VERSION_CODE.apk"
aab="$(ls app/build/outputs/bundle/fdroidRelease/*.aab | head -1)"
cp "$aab" "../dist/com.kikaron.multipass.aab"
echo "built ../dist/kikaron-multipass-$version-$MULTIPASS_VERSION_CODE.apk and ../dist/com.kikaron.multipass.aab"
