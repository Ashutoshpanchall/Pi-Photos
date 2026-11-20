#!/usr/bin/env bash
# APK Size & Composition Inspector (Bash)
# Target: Release APK < 15 MB

APK_PATH="${1:-app/build/outputs/apk/release/app-release-unsigned.apk}"

if [[ ! -f "$APK_PATH" ]]; then
    echo "Release APK not found at $APK_PATH. Building release APK..."
    ./gradlew assembleRelease
fi

if [[ ! -f "$APK_PATH" ]]; then
    echo "Error: Release APK not found at $APK_PATH" >&2
    exit 1
fi

SIZE_BYTES=$(stat -c%s "$APK_PATH" 2>/dev/null || stat -f%z "$APK_PATH" 2>/dev/null)
SIZE_MB=$(awk "BEGIN {printf \"%.2f\", $SIZE_BYTES / (1024 * 1024)}")

echo "===================================================="
echo " Release APK Size Analysis"
echo " File:   $APK_PATH"
echo " Size:   ${SIZE_MB} MB (${SIZE_BYTES} bytes)"
echo " Target: < 15.0 MB"
if (( $(echo "$SIZE_MB < 15.0" | bc -l) )); then
    echo " Status: PASS"
else
    echo " Status: FAIL"
fi
echo "===================================================="

echo ""
echo "Top 10 Largest Contributors Inside APK:"
unzip -l "$APK_PATH" 2>/dev/null | sort -nr -k1 | head -n 10
echo "----------------------------------------------------"
