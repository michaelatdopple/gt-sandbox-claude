#!/bin/bash
set -e

# BLE Device Test Script
# Runs automated BLE tests between two connected devices
#
# Usage:
#   ./run_ble_test.sh --host <serial> --client <serial>
#   ./run_ble_test.sh -h <serial> -c <serial>
#
# Environment variables HOST_DEVICE / CLIENT_DEVICE are also accepted.

usage() {
    echo "Usage: $0 --host <serial> --client <serial>"
    echo ""
    echo "Options:"
    echo "  -h, --host    Host device serial number"
    echo "  -c, --client  Client device serial number"
    echo "  --help        Show this help"
    echo ""
    echo "Device serials can also be set via HOST_DEVICE / CLIENT_DEVICE env vars."
    echo "Run 'adb devices' to list connected devices."
    exit 1
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--host)    HOST_DEVICE="$2"; shift 2 ;;
        -c|--client)  CLIENT_DEVICE="$2"; shift 2 ;;
        --help)       usage ;;
        *)            echo "Unknown option: $1"; usage ;;
    esac
done

if [[ -z "$HOST_DEVICE" || -z "$CLIENT_DEVICE" ]]; then
    echo "ERROR: Both --host and --client device serials are required."
    echo ""
    usage
fi

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}=== BLE Device Test ===${NC}"
echo "Host device: $HOST_DEVICE"
echo "Client device: $CLIENT_DEVICE"
echo ""

# Verify both devices are connected
echo "Checking devices..."
if ! adb -s "$HOST_DEVICE" get-state > /dev/null 2>&1; then
    echo -e "${RED}ERROR: Host device $HOST_DEVICE not found${NC}"
    exit 1
fi

if ! adb -s "$CLIENT_DEVICE" get-state > /dev/null 2>&1; then
    echo -e "${RED}ERROR: Client device $CLIENT_DEVICE not found${NC}"
    exit 1
fi

echo -e "${GREEN}Both devices connected${NC}"
echo ""

# Build and install (installDebug installs to all connected devices)
echo "Building and installing APK..."
./gradlew installDebug -q

echo -e "${GREEN}APK installed on both devices${NC}"
echo ""

# Grant BLE and Location permissions (Location required for BLE scanning on Android)
echo "Granting permissions..."
for DEVICE in "$HOST_DEVICE" "$CLIENT_DEVICE"; do
    adb -s "$DEVICE" shell pm grant com.dopple.webview android.permission.BLUETOOTH_SCAN 2>/dev/null || true
    adb -s "$DEVICE" shell pm grant com.dopple.webview android.permission.BLUETOOTH_CONNECT 2>/dev/null || true
    adb -s "$DEVICE" shell pm grant com.dopple.webview android.permission.BLUETOOTH_ADVERTISE 2>/dev/null || true
    adb -s "$DEVICE" shell pm grant com.dopple.webview android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
    adb -s "$DEVICE" shell pm grant com.dopple.webview android.permission.ACCESS_COARSE_LOCATION 2>/dev/null || true
done
echo -e "${GREEN}Permissions granted${NC}"
echo ""

# Wake screens and keep them on
echo "Waking screens..."
for DEVICE in "$HOST_DEVICE" "$CLIENT_DEVICE"; do
    adb -s "$DEVICE" shell input keyevent KEYCODE_WAKEUP
    adb -s "$DEVICE" shell svc power stayon true 2>/dev/null || true
done
echo -e "${GREEN}Screens awake${NC}"
echo ""

# Clear old logs
adb -s "$HOST_DEVICE" logcat -c
adb -s "$CLIENT_DEVICE" logcat -c

# Launch host first
echo "Launching HOST..."
adb -s "$HOST_DEVICE" shell am start \
    -a android.intent.action.VIEW \
    -d "dopple://test/ble?role=host" \
    com.dopple.webview

# Wait for host to start advertising (Host-as-Server architecture)
echo "Waiting for host to be ready..."
sleep 3

# Launch client
echo "Launching CLIENT..."
adb -s "$CLIENT_DEVICE" shell am start \
    -a android.intent.action.VIEW \
    -d "dopple://test/ble?role=client" \
    com.dopple.webview

echo ""
echo -e "${YELLOW}Test running... Monitoring logs:${NC}"
echo ""

# Create temp files for log output
HOST_LOG=$(mktemp)
CLIENT_LOG=$(mktemp)

# Start log monitoring in background
adb -s "$HOST_DEVICE" logcat -s "BleTest:*" > "$HOST_LOG" 2>&1 &
HOST_PID=$!

adb -s "$CLIENT_DEVICE" logcat -s "BleTest:*" > "$CLIENT_LOG" 2>&1 &
CLIENT_PID=$!

# Wait for test completion (max 30 seconds)
TIMEOUT=30
ELAPSED=0
TEST_COMPLETE=false

while [ $ELAPSED -lt $TIMEOUT ]; do
    sleep 1
    ELAPSED=$((ELAPSED + 1))

    # Check for completion markers
    if grep -q "TEST COMPLETE" "$HOST_LOG" 2>/dev/null; then
        TEST_COMPLETE=true
        break
    fi
done

# Kill log processes
kill $HOST_PID 2>/dev/null || true
kill $CLIENT_PID 2>/dev/null || true

echo ""
echo -e "${YELLOW}=== HOST LOG ===${NC}"
cat "$HOST_LOG"

echo ""
echo -e "${YELLOW}=== CLIENT LOG ===${NC}"
cat "$CLIENT_LOG"

echo ""

# Parse results
if grep -q "PASSED" "$HOST_LOG" 2>/dev/null; then
    echo -e "${GREEN}=== TEST PASSED ===${NC}"
    EXIT_CODE=0
else
    echo -e "${RED}=== TEST FAILED ===${NC}"
    EXIT_CODE=1
fi

# Cleanup
rm -f "$HOST_LOG" "$CLIENT_LOG"

exit $EXIT_CODE
