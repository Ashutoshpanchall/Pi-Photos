#!/usr/bin/env bash
# Startup Trace Capture Script (Bash)
# Captures system and app traces during cold start for Perfetto analysis

PACKAGE="${1:-com.example.piphotos}"
ACTIVITY="${2:-.MainActivity}"
OUTPUT_FILE="${3:-startup_trace.perfetto-trace}"

echo "===================================================="
echo " Capturing Startup Trace for ${PACKAGE} (${OUTPUT_FILE})"
echo "===================================================="

adb shell am force-stop "$PACKAGE"
sleep 1

echo "Starting background Perfetto trace recording (5s)..."
adb shell perfetto -o /data/misc/perfetto-traces/trace.perfetto-trace -t 5s sched freq idle am wm gfx view binder_driver app &
PERFETTO_PID=$!

sleep 0.8

echo "Launching app cold start..."
adb shell am start -W -n "${PACKAGE}/${ACTIVITY}"

wait $PERFETTO_PID 2>/dev/null

echo "Pulling trace file to local machine..."
adb pull /data/misc/perfetto-traces/trace.perfetto-trace "$OUTPUT_FILE"
adb shell rm -f /data/misc/perfetto-traces/trace.perfetto-trace

if [[ -f "$OUTPUT_FILE" ]]; then
    echo "Saved trace: $OUTPUT_FILE"
    echo "Inspect at https://ui.perfetto.dev"
fi
echo "----------------------------------------------------"
