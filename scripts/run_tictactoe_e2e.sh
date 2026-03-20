#!/bin/bash
set -e

# Tic-Tac-Toe E2E Test Script
# Runs automated E2E tests between two physical devices using:
# - ADB intents to launch the game
# - CDP (Chrome DevTools Protocol) to drive the WebView UI
# - BLE for actual device-to-device communication
#
# Usage:
#   ./run_tictactoe_e2e.sh --host <serial> --client <serial>
#   ./run_tictactoe_e2e.sh -h <serial> -c <serial>
#
# Environment variables HOST_DEVICE / CLIENT_DEVICE are also accepted.

# CDP ports (override via env if needed)
HOST_CDP_PORT="${HOST_CDP_PORT:-9222}"
CLIENT_CDP_PORT="${CLIENT_CDP_PORT:-9223}"

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
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo -e "${CYAN}=== Tic-Tac-Toe E2E Test ===${NC}"
echo "Host device: $HOST_DEVICE (CDP port: $HOST_CDP_PORT)"
echo "Client device: $CLIENT_DEVICE (CDP port: $CLIENT_CDP_PORT)"
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
    adb -s "$DEVICE" shell pm grant com.dopple.webview android.permission.CAMERA 2>/dev/null || true
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

# Force stop any existing instances
echo "Stopping existing app instances..."
adb -s "$HOST_DEVICE" shell am force-stop com.dopple.webview
adb -s "$CLIENT_DEVICE" shell am force-stop com.dopple.webview
sleep 1

# Launch tictactoe on HOST device
# Use localhost:8088 URL which triggers the AssetHttpServer
echo -e "${YELLOW}Launching tictactoe on HOST...${NC}"
adb -s "$HOST_DEVICE" shell am start \
    -a android.intent.action.VIEW \
    -d "dopple://launch?manifest=http://127.0.0.1:8088/games/tictactoe/manifest.json" \
    com.dopple.webview

# Wait for host to initialize
sleep 2

# Launch tictactoe on CLIENT device
echo -e "${YELLOW}Launching tictactoe on CLIENT...${NC}"
adb -s "$CLIENT_DEVICE" shell am start \
    -a android.intent.action.VIEW \
    -d "dopple://launch?manifest=http://127.0.0.1:8088/games/tictactoe/manifest.json" \
    com.dopple.webview

# Wait for WebViews to initialize
echo "Waiting for WebViews to initialize..."
sleep 3

# Get app PIDs
echo "Setting up CDP port forwarding..."
HOST_PID=$(adb -s "$HOST_DEVICE" shell pidof com.dopple.webview | tr -d '\r')
CLIENT_PID=$(adb -s "$CLIENT_DEVICE" shell pidof com.dopple.webview | tr -d '\r')

if [ -z "$HOST_PID" ]; then
    echo -e "${RED}ERROR: Could not get HOST app PID${NC}"
    exit 1
fi

if [ -z "$CLIENT_PID" ]; then
    echo -e "${RED}ERROR: Could not get CLIENT app PID${NC}"
    exit 1
fi

echo "Host PID: $HOST_PID, Client PID: $CLIENT_PID"

# Clear any existing port forwards
adb -s "$HOST_DEVICE" forward --remove-all 2>/dev/null || true
adb -s "$CLIENT_DEVICE" forward --remove-all 2>/dev/null || true

# Forward CDP ports
# WebView exposes debugging via localabstract socket
adb -s "$HOST_DEVICE" forward tcp:$HOST_CDP_PORT localabstract:webview_devtools_remote_$HOST_PID
adb -s "$CLIENT_DEVICE" forward tcp:$CLIENT_CDP_PORT localabstract:webview_devtools_remote_$CLIENT_PID

echo -e "${GREEN}CDP ports forwarded: HOST=$HOST_CDP_PORT, CLIENT=$CLIENT_CDP_PORT${NC}"
echo ""

# Verify CDP endpoints are accessible
echo "Verifying CDP endpoints..."
if ! curl -s "http://localhost:$HOST_CDP_PORT/json" > /dev/null 2>&1; then
    echo -e "${RED}ERROR: Cannot connect to HOST CDP at port $HOST_CDP_PORT${NC}"
    echo "Make sure WebView debugging is enabled in the app"
    exit 1
fi

if ! curl -s "http://localhost:$CLIENT_CDP_PORT/json" > /dev/null 2>&1; then
    echo -e "${RED}ERROR: Cannot connect to CLIENT CDP at port $CLIENT_CDP_PORT${NC}"
    echo "Make sure WebView debugging is enabled in the app"
    exit 1
fi

echo -e "${GREEN}CDP endpoints verified${NC}"
echo ""

# Run Node.js test driver
echo -e "${CYAN}=== Running E2E Test Driver ===${NC}"
echo ""

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOST_CDP_PORT=$HOST_CDP_PORT CLIENT_CDP_PORT=$CLIENT_CDP_PORT node "$SCRIPT_DIR/tictactoe_e2e_driver.mjs"
EXIT_CODE=$?

# Cleanup
echo ""
echo "Cleaning up..."
adb -s "$HOST_DEVICE" forward --remove-all 2>/dev/null || true
adb -s "$CLIENT_DEVICE" forward --remove-all 2>/dev/null || true

if [ $EXIT_CODE -eq 0 ]; then
    echo -e "${GREEN}=== TEST PASSED ===${NC}"
else
    echo -e "${RED}=== TEST FAILED ===${NC}"
fi

exit $EXIT_CODE
