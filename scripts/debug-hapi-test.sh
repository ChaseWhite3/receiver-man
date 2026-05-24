#!/usr/bin/env bash
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_NAME="org.receiverman.ReceiverManHapiReceiverTest.hapiSimpleServerCanFeedReceiverManBus"

pause_if_possible() {
  if [[ -t 0 ]]; then
    read -r -p "Press Enter to close this window..." _ || true
  fi
}

cd "$ROOT_DIR"

echo "Starting HAPI ReceiverMan debug test..."
echo "Test: $TEST_NAME"
echo "Debugger port: 5005"
echo
echo "When Gradle prints that it is listening, attach VS Code to port 5005."
echo

./gradlew debugHapiTest --no-configuration-cache

status=$?
echo

if [[ $status -ne 0 ]]; then
  echo "Debug test failed before or during execution. Exit code: $status"
  echo "Read the Gradle error above. If it mentions HAPI dependencies, Maven Central resolution is failing locally."
  pause_if_possible
  exit "$status"
fi

echo "Debug test finished."
pause_if_possible
