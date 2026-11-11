# 에뮬레이터에 가짜 주행 위치를 1초마다 넣어서, 앱이 실제 Pleos 위치 SDK로 받아 안내하는 흐름을 시연한다.
# 사용법 (PowerShell):
#   .\tools\demo-drive.ps1                 # 개발용 입구(dev-001)로 다가가서 도착
#   .\tools\demo-drive.ps1 -Scenario pass  # 입구를 지나쳤다가 멈춤
# 도착 후 Virtual Console(http://127.0.0.1:8088/)에서 기어를 P로 바꾸면 주차 완료 화면이 뜬다.
# 실제 장소 데이터로 바꾸면 아래 시작·끝 좌표를 그 장소 접근 경로로 바꾼다.

param(
    [ValidateSet("arrive", "pass")]
    [string]$Scenario = "arrive",
    [double]$Lng = 127.09468,
    [double]$StartLat = 37.41000,
    [double]$EntranceLat = 37.41450,
    [double]$SpeedMps = 8.0
)

$degPerSec = $SpeedMps / 111000.0
$endLat = if ($Scenario -eq "pass") { $EntranceLat + 0.00150 } else { $EntranceLat }

Write-Host "시나리오: $Scenario, 시작 $StartLat -> 끝 $endLat, 초속 $SpeedMps m"
$lat = $StartLat
while ($lat -lt $endLat) {
    $fix = "{0:F6} {1:F6}" -f $Lng, $lat   # geo fix 는 경도, 위도 순서
    & adb emu geo fix $Lng.ToString("F6") $lat.ToString("F6") | Out-Null
    $distance = [math]::Round(($EntranceLat - $lat) * 111000)
    Write-Host ("위도 {0:F5}  입구까지 약 {1}m" -f $lat, $distance)
    Start-Sleep -Seconds 1
    $lat += $degPerSec
}
& adb emu geo fix $Lng.ToString("F6") $endLat.ToString("F6") | Out-Null
Write-Host "정지. 도착 시나리오면 Virtual Console에서 기어를 P로 바꾸세요."
