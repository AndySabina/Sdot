#!/bin/sh
# Build a signed release APK for a GitHub release. Publishing stays manual: this script only
# prints the suggested `gh release create` command.
#
# Required environment:
#   SDOT_UPDATE_REPO        owner/repo the in-app updater checks
#   SDOT_VERSION            release version, MAJOR.MINOR.PATCH (no leading v)
#   SDOT_KEYSTORE           path to the release keystore (keep it outside the repository)
#   SDOT_KEYSTORE_PASSWORD  keystore password
#   SDOT_KEY_ALIAS          key alias inside the keystore
#   SDOT_KEY_PASSWORD       key password
set -eu

missing=""
for name in SDOT_UPDATE_REPO SDOT_VERSION SDOT_KEYSTORE SDOT_KEYSTORE_PASSWORD SDOT_KEY_ALIAS SDOT_KEY_PASSWORD; do
    eval "value=\${$name:-}"
    if [ -z "$value" ]; then
        missing="$missing $name"
    fi
done
if [ -n "$missing" ]; then
    echo "Missing environment variables:$missing" >&2
    exit 1
fi

case "$SDOT_VERSION" in
    v*) echo "SDOT_VERSION must not start with v (use 1.2.3)" >&2; exit 1 ;;
esac
if [ ! -f "$SDOT_KEYSTORE" ]; then
    echo "SDOT_KEYSTORE does not point to a file" >&2
    exit 1
fi

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root/android"

# Signing values reach Gradle through the environment, never the command line.
./gradlew :app:testDebugUnitTest :app:assembleRelease \
    "-Psdot.updateRepo=$SDOT_UPDATE_REPO" \
    "-Psdot.versionName=$SDOT_VERSION"

out="app/build/outputs/apk/release"
apk="$out/sdot-$SDOT_VERSION.apk"
cp "$out/app-release.apk" "$apk"

if command -v sha256sum >/dev/null 2>&1; then
    sum=$(sha256sum "$apk" | cut -d ' ' -f 1)
else
    sum=$(shasum -a 256 "$apk" | cut -d ' ' -f 1)
fi

echo
echo "APK:    android/$apk"
echo "SHA256: $sum"
echo
echo "Review, then publish with:"
echo "  gh release create v$SDOT_VERSION android/$apk --repo $SDOT_UPDATE_REPO --title \"Sdot v$SDOT_VERSION\" --notes \"SHA256: $sum\""
