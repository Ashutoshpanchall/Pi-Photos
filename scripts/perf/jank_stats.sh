#!/usr/bin/env bash
# JankStats Measurement Script (Bash)
# Target: Janky frames < 2.0% at 120 Hz (8.33 ms budget) on Motorola Edge 50 Neo

PACKAGE="${1:-com.example.piphotos}"
FLING_COUNT="${2:-5}"

echo "===================================================="
echo " Measuring Frame Jank for ${PACKAGE} (${FLING_COUNT} flings)"
echo "===================================================="

# Ensure app is in foreground
adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 2

# Reset gfxinfo
echo "Resetting gfxinfo counters..."
adb shell dumpsys gfxinfo "$PACKAGE" reset >/dev/null 2>&1
sleep 0.5

# Motorola Edge 50 Neo coordinates
START_X=600
START_Y=1800
END_X=600
END_Y=500

for ((i=1; i<=FLING_COUNT; i++)); do
    echo "Executing fling $i of $FLING_COUNT..."
    adb shell input swipe $START_X $START_Y $END_X $END_Y 150
    sleep 1.2
    adb shell input swipe $START_X $END_Y $END_X $START_Y 150
    sleep 1.2
done

echo "Collecting framestats from dumpsys gfxinfo..."
STATS=$(adb shell dumpsys gfxinfo "$PACKAGE")

TOTAL_FRAMES=$(echo "$STATS" | grep -E "Total frames rendered:" | awk '{print $4}' | tr -d '\r')
JANKY_FRAMES=$(echo "$STATS" | grep -E "Janky frames:" | awk '{print $3}' | tr -d '\r')

echo "----------------------------------------------------"
echo "Gfxinfo Results:"
echo "  Total frames:  $TOTAL_FRAMES"
echo "  Janky frames:  $JANKY_FRAMES"

if [[ -n "$TOTAL_FRAMES" && "$TOTAL_FRAMES" -gt 0 ]]; then
    JANK_PCT=$(awk "BEGIN {printf \"%.2f\", ($JANKY_FRAMES / $TOTAL_FRAMES) * 100}")
    echo "  Jank rate:     $JANK_PCT %"
    echo "Target: < 2.0% at 120 Hz"
fi
echo "----------------------------------------------------"
