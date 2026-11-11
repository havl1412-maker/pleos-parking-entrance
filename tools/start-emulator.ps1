# Pleos 에뮬레이터 켜기 (노트북용)
# 이 노트북에서는 에뮬레이터가 DNS를 못 찾아 내비가 "서비스 연결 중"에 멈춘다 → DNS 서버를 직접 지정해서 켠다.
# Android Studio Device Manager의 실행 버튼으로 켜면 이 옵션이 빠지니 이 파일로 켤 것.
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
Start-Process "$sdk\emulator\emulator.exe" -ArgumentList "-avd", "Pleos_Connect_v2.1.0", "-dns-server", "8.8.8.8,1.1.1.1"
& $adb wait-for-device
while ((& $adb shell getprop sys.boot_completed 2>$null) -ne "1") { Start-Sleep 3 }
& $adb forward tcp:8088 tcp:8088 | Out-Null
Write-Host "부팅 완료. Virtual Console: http://127.0.0.1:8088/"
