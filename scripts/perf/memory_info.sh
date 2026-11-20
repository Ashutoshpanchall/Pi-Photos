#!/usr/bin/env bash
# Memory Measurement Script (Bash)
# Target: Peak heap while scrolling < 150 MB on Motorola Edge 50 Neo

PACKAGE="${1:-com.example.piphotos}"

echo "===================================================="
echo " Measuring Memory Usage for ${PACKAGE}"
echo "===================================================="

# Ensure app is active
adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 1

# Fling grid
echo "Exercising thumbnail memory cache with grid flings..."
for i in {1..3}; do
    adb shell input swipe 600 1800 600 500 150
    sleep 0.8
    adb shell input swipe 600 500 600 1800 150
    sleep 0.8
done

echo "Fetching dumpsys meminfo..."
MEMINFO=$(adb shell dumpsys meminfo "$PACKAGE")

TOTAL_PSS_KB=$(echo "$MEMINFO" | grep -E "TOTAL PSS:" | awk '{print $3}' | tr -d '\r')
NATIVE_HEAP_KB=$(echo "$MEMINFO" | grep -E "Native Heap" | head -n 1 | awk '{print $3}' | tr -d '\r')
DALVIK_HEAP_KB=$(echo "$MEMINFO" | grep -E "Dalvik Heap" | head -n 1 | awk '{print $3}' | tr -d '\r')

if [[ -n "$TOTAL_PSS_KB" ]]; then
    TOTAL_PSS_MB=$(awk "BEGIN {printf \"%.1f\", $TOTAL_PSS_KB / 1024}")
    echo "----------------------------------------------------"
    echo "Total PSS:    ${TOTAL_PSS_MB} MB"
    echo "Target:       < 150 MB"
    echo "----------------------------------------------------"
fi
