#!/bin/bash
set -e

# BLE Edge Case Test Script
# Tests BLE stack idempotency via native test fragment scenarios.
#
# Usage:
#   ./run_ble_edge_cases.sh --host <serial> --client <serial>

HOST_CDP_PORT="${HOST_CDP_PORT:-9222}"
CLIENT_CDP_PORT="${CLIENT_CDP_PORT:-9223}"

usage() {
    echo "Usage: $0 --host <serial> --client <serial>"
    echo ""
    echo "Options:"
    echo "  -h, --host    Host device serial number"
    echo "  -c, --client  Client device serial number"
    echo "  --help        Show this help"
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

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}=== BLE Edge Case Tests ===${NC}"
echo "Host: $HOST_DEVICE | Client: $CLIENT_DEVICE"
echo ""

# Verify devices
for DEV in "$HOST_DEVICE" "$CLIENT_DEVICE"; do
    if ! adb -s "$DEV" get-state > /dev/null 2>&1; then
        echo -e "${RED}ERROR: Device $DEV not found${NC}"
        exit 1
    fi
done
echo -e "${GREEN}Both devices connected${NC}"

# Build and install
echo "Building and installing..."
./gradlew installDebug -q
echo -e "${GREEN}APK installed${NC}"

# Permissions
for DEV in "$HOST_DEVICE" "$CLIENT_DEVICE"; do
    adb -s "$DEV" shell pm grant com.dopple.webview android.permission.BLUETOOTH_SCAN 2>/dev/null || true
    adb -s "$DEV" shell pm grant com.dopple.webview android.permission.BLUETOOTH_CONNECT 2>/dev/null || true
    adb -s "$DEV" shell pm grant com.dopple.webview android.permission.BLUETOOTH_ADVERTISE 2>/dev/null || true
    adb -s "$DEV" shell pm grant com.dopple.webview android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
    adb -s "$DEV" shell pm grant com.dopple.webview android.permission.ACCESS_COARSE_LOCATION 2>/dev/null || true
    adb -s "$DEV" shell input keyevent KEYCODE_WAKEUP
    adb -s "$DEV" shell svc power stayon true 2>/dev/null || true
done
echo -e "${GREEN}Permissions granted, screens awake${NC}"
echo ""

# Run Node.js driver
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOST_DEVICE=$HOST_DEVICE CLIENT_DEVICE=$CLIENT_DEVICE \
  node "$SCRIPT_DIR/ble_edge_cases.mjs"
EXIT_CODE=$?

if [ $EXIT_CODE -eq 0 ]; then
    echo -e "${GREEN}=== ALL BLE EDGE CASE TESTS PASSED ===${NC}"
else
    echo -e "${RED}=== BLE EDGE CASE TESTS FAILED ===${NC}"
fi

exit $EXIT_CODE
