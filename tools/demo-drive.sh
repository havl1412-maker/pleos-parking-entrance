#!/usr/bin/env bash
# 에뮬레이터에 가짜 주행 위치를 1초마다 넣어서, 앱이 실제 Pleos 위치 SDK로 받아 안내하는 흐름을 시연한다. (맥·리눅스용)
# 사용법: ./tools/demo-drive.sh [arrive|pass]
# 도착 후 Virtual Console(http://127.0.0.1:8088/)에서 기어를 P로 바꾸면 주차 완료 화면이 뜬다.
set -euo pipefail

SCENARIO="${1:-arrive}"
# 환경 변수로 바꿀 수 있다. 예: START_LAT=37.4110 SPEED_MPS=12 ./tools/demo-drive.sh arrive
LNG="${LNG:-127.09468}"
START_LAT="${START_LAT:-37.41000}"
ENTRANCE_LAT="${ENTRANCE_LAT:-37.41450}"
SPEED_MPS="${SPEED_MPS:-8.0}"

END_LAT=$(awk -v e="$ENTRANCE_LAT" -v s="$SCENARIO" 'BEGIN { printf "%.6f", (s == "pass") ? e + 0.0015 : e }')
STEP=$(awk -v v="$SPEED_MPS" 'BEGIN { printf "%.8f", v / 111000.0 }')

echo "시나리오: $SCENARIO, 시작 $START_LAT -> 끝 $END_LAT"
lat=$START_LAT
while awk -v a="$lat" -v b="$END_LAT" 'BEGIN { exit !(a < b) }'; do
  adb emu geo fix "$LNG" "$lat" > /dev/null   # 경도, 위도 순서
  dist=$(awk -v e="$ENTRANCE_LAT" -v l="$lat" 'BEGIN { printf "%d", (e - l) * 111000 }')
  echo "위도 $lat  입구까지 약 ${dist}m"
  sleep 1
  lat=$(awk -v a="$lat" -v s="$STEP" 'BEGIN { printf "%.6f", a + s }')
done
adb emu geo fix "$LNG" "$END_LAT" > /dev/null
echo "정지. 도착 시나리오면 Virtual Console에서 기어를 P로 바꾸세요."
