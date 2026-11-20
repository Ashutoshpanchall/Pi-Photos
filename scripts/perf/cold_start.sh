#!/usr/bin/env bash
# Cold Start Measurement Script (Bash)
# Target: < 1000 ms to first grid frame on Motorola Edge 50 Neo

PACKAGE="${1:-com.example.piphotos}"
ACTIVITY="${2:-.MainActivity}"
RUNS="${3:-10}"

echo "===================================================="
echo " Measuring Cold Start for ${PACKAGE}/${ACTIVITY} (${RUNS} runs)"
echo "===================================================="

times=()

for ((i=1; i<=RUNS; i++)); do
    adb shell am force-stop "$PACKAGE"
    sleep 1.5

    output=$(adb shell am start -W -n "${PACKAGE}/${ACTIVITY}")
    total_time=$(echo "$output" | grep -E "TotalTime:" | awk '{print $2}' | tr -d '\r')

    if [[ -n "$total_time" ]]; then
        times+=("$total_time")
        echo "Run $i: ${total_time} ms"
    else
        echo "Run $i: Failed to parse TotalTime"
    fi
done

if [ ${#times[@]} -gt 0 ]; then
    IFS=$'\n' sorted=($(sort -n <<<"${times[*]}"))
    unset IFS
    count=${#sorted[@]}
    median=${sorted[$((count / 2))]}

    echo "----------------------------------------------------"
    echo "Cold Start Results over $count runs:"
    echo "  Median: ${median} ms"
    echo "Target: < 1000 ms"
    if [ "$median" -lt 1000 ]; then
        echo "Status: PASS"
    else
        echo "Status: WARN (Above 1000 ms target)"
    fi
    echo "----------------------------------------------------"
else
    echo "Error: No runs completed. Ensure device is connected via adb." >&2
    exit 1
fi
