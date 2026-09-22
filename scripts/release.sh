#!/usr/bin/env bash
#
# Builds, signs and publishes a release from this machine.
#
# Pushing a v* tag also runs .github/workflows/release.yml, which does the same on GitHub. Whichever
# finishes first creates the release; the other uploads its APKs over the existing assets.
#
# Usage:
#   scripts/release.sh v1.2.0          # build, sign, verify, and publish the GitHub release
#   scripts/release.sh v1.2.0 --dry    # build, sign and verify, but publish nothing
#
# Expects the signing keystore at $KEYSTORE (default below) and its passwords in the environment:
#   KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
# If they are unset you are prompted, so nothing lands in shell history.

set -euo pipefail

TAG="${1:-}"
DRY="${2:-}"
REPO="mKonic/yomikku"
KEYSTORE="${KEYSTORE:-$HOME/dev/android/keys/yomikku-release-key.keystore}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_HOME

if [ -z "$TAG" ]; then
    echo "usage: scripts/release.sh <tag> [--dry]" >&2
    exit 2
fi

cd "$(dirname "$0")/.."

# --- preflight -------------------------------------------------------------

[ -f "$KEYSTORE" ] || { echo "No keystore at $KEYSTORE" >&2; exit 1; }

if [ -n "$(git status --porcelain)" ]; then
    echo "Working tree is dirty. Commit or stash first - the version name comes from git." >&2
    exit 1
fi

if ! git rev-parse "$TAG" >/dev/null 2>&1; then
    echo "Tag $TAG does not exist. Create it first, so the build stamps the right version." >&2
    exit 1
fi

if [ "$(git rev-parse HEAD)" != "$(git rev-parse "$TAG^{commit}")" ]; then
    echo "HEAD is not $TAG. Check it out first, or the APK will be stamped from the wrong commit." >&2
    exit 1
fi

BUILD_TOOLS="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
APKSIGNER="$BUILD_TOOLS/apksigner"
[ -x "$APKSIGNER" ] || { echo "No apksigner under $BUILD_TOOLS" >&2; exit 1; }

# The password comes from a YOMIKKU_KEY=... line in a .env kept beside the keystore, outside the repo.
# Override the location with KEYS_ENV, or set KEYSTORE_PASSWORD directly to skip the file.
ENV_FILE="${KEYS_ENV:-$(dirname "$KEYSTORE")/.env}"
if [ -z "${KEYSTORE_PASSWORD:-}" ] && [ -f "$ENV_FILE" ]; then
    KEYSTORE_PASSWORD="$(grep -E '^YOMIKKU_KEY=' "$ENV_FILE" | head -1 | cut -d= -f2- | tr -d '\r\n')"
fi
if [ -z "${KEYSTORE_PASSWORD:-}" ]; then
    read -rsp 'Keystore password: ' KEYSTORE_PASSWORD
    echo >&2
fi

# Read the alias out of the keystore rather than storing it: it is not a secret, and one fewer
# thing to keep in sync is one fewer way for a release to fail at the signing step.
if [ -z "${KEY_ALIAS:-}" ]; then
    KEY_ALIAS="$(keytool -list -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASSWORD" 2>/dev/null |
        awk -F, '/PrivateKeyEntry/ { print $1; exit }')"
fi
[ -n "$KEY_ALIAS" ] || { echo "Could not read a key alias from $KEYSTORE - wrong password?" >&2; exit 1; }
: "${KEY_PASSWORD:=$KEYSTORE_PASSWORD}"

echo "==> Signing as '$KEY_ALIAS'"

# --- verify and build ------------------------------------------------------

echo "==> Verifying and building"
./gradlew --max-workers=4 spotlessCheck testDebugUnitTest :app:lintDebug :app:assembleRelease -Penable-updater

shopt -s nullglob
UNSIGNED=(app/build/outputs/apk/release/*-release-unsigned.apk)
[ ${#UNSIGNED[@]} -gt 0 ] || { echo "No unsigned APK was produced" >&2; exit 1; }

rm -rf dist
mkdir -p dist
SIGNED=()
for apk in "${UNSIGNED[@]}"; do
    abi="$(basename "$apk" | sed -E 's/^app-(.*)-release-unsigned\.apk$/\1/')"
    # The in-app updater picks the asset naming the device's ABI and falls back to the one that
    # names none, so the universal APK must not say "universal".
    if [ "$abi" = universal ]; then
        out="dist/Yomikku-${TAG}.apk"
    else
        out="dist/Yomikku-${abi}-${TAG}.apk"
    fi
    "$APKSIGNER" sign \
        --ks "$KEYSTORE" \
        --ks-pass "pass:$KEYSTORE_PASSWORD" \
        --ks-key-alias "$KEY_ALIAS" \
        --key-pass "pass:$KEY_PASSWORD" \
        --out "$out" \
        "$apk"
    SIGNED+=("$out")
done

# --- verify the artifacts, not just the build ------------------------------

echo "==> Verifying the signed APKs"
for apk in "${SIGNED[@]}"; do
    "$APKSIGNER" verify --verbose "$apk" | grep -qE "^Verified using v[23] scheme \(APK Signature Scheme v[23]\): true" || {
        echo "Signature did not verify: $apk" >&2
        exit 1
    }
done

# A library that is not 16 KB aligned fails to load on a 16 KB-page device, which is a launch crash
# rather than a degraded mode. Only 64-bit libraries can end up on such a device.
echo "==> Checking native library alignment"
TMP="$(mktemp -d)"
NOTES="$(mktemp)"
trap 'rm -rf "$TMP" "$NOTES"' EXIT
BAD=0
CHECKED=0
for apk in "${SIGNED[@]}"; do
    rm -rf "${TMP:?}"/*
    unzip -oq "$apk" 'lib/arm64-v8a/*' 'lib/x86_64/*' -d "$TMP" 2>/dev/null || true
    for so in "$TMP"/lib/*/*.so; do
        ALIGN="$(readelf -lW "$so" | awk '/LOAD/{print $NF}' | sort -u | tr -d '\n')"
        CHECKED=$((CHECKED + 1))
        if [ "$ALIGN" != "0x4000" ]; then
            echo "   NOT 16 KB ALIGNED: $(basename "$apk") $(basename "$so") ($ALIGN)" >&2
            BAD=1
        fi
    done
done
[ "$BAD" -eq 0 ] || { echo "Refusing to publish: see above." >&2; exit 1; }
echo "   all $CHECKED 64-bit libraries are 16 KB aligned"

for apk in "${SIGNED[@]}"; do
    VERSION="$("$BUILD_TOOLS/aapt2" dump badging "$apk" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")"
    CODE="$("$BUILD_TOOLS/aapt2" dump badging "$apk" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p")"
    echo "==> $apk  ($(du -h "$apk" | cut -f1))  versionName=$VERSION versionCode=$CODE"
done

if [ "$DRY" = "--dry" ]; then
    echo "==> Dry run, nothing published."
    exit 0
fi

# Release notes come from CHANGELOG.md's section for this tag, so the release page reads like the
# changelog rather than a dump of commit subjects.
# Matched literally: a tag's dots and any +build suffix are not regex.
awk -v heading="## [$TAG]" '
    index($0, heading) == 1 { found = 1; next }
    found && /^## \[/ { exit }
    found { print }
' CHANGELOG.md | sed -e '/./,$!d' > "$NOTES"

# A semver pre-release (a -suffix ahead of any +build) is published as one, which also keeps it out of
# the in-app update check.
PRERELEASE=()
case "${TAG%%+*}" in *-*) PRERELEASE=(--prerelease) ;; esac

echo "==> Publishing $TAG to $REPO"
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
    echo "   $TAG already exists (CI got there first); uploading over its assets."
    gh release upload "$TAG" "${SIGNED[@]}" --repo "$REPO" --clobber
    if [ -s "$NOTES" ]; then
        gh release edit "$TAG" --repo "$REPO" --notes-file "$NOTES"
    fi
elif [ -s "$NOTES" ]; then
    gh release create "$TAG" "${SIGNED[@]}" "${PRERELEASE[@]}" \
        --repo "$REPO" \
        --title "Yomikku $TAG" \
        --notes-file "$NOTES"
else
    echo "   No '## [$TAG]' section in CHANGELOG.md; falling back to generated notes." >&2
    gh release create "$TAG" "${SIGNED[@]}" "${PRERELEASE[@]}" \
        --repo "$REPO" \
        --title "Yomikku $TAG" \
        --generate-notes
fi

echo "==> Done: $(gh release view "$TAG" --repo "$REPO" --json url --jq .url)"
