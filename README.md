# 주차장 입구 안내 (가칭)

상가가 밀집한 곳에서 내비가 목적지 근처까지 데려다줘도, 붙어 있는 경사로 중 어느 것이 진짜 주차장 입구인지 헷갈리는 순간을 돕는 Pleos Connect 차량용 앱입니다. 도착 직전에 실제 입구 사진, 옆 건물 입구와 헷갈림 경고, 큰 화살표와 음성으로 "어느 입구로 들어가야 하는지"를 알려 줍니다.

> 개발 중. 앱 이름, 실행 방법, APK 위치는 구현이 진행되면 채웁니다.

## 화면

| 300m 전 미리 보기 | 입구 앞 | 지나쳤을 때 | 주차 완료 |
|---|---|---|---|
| ![](docs/images/01-approach-300m.png) | ![](docs/images/02-at-entrance.png) | ![](docs/images/03-passed.png) | ![](docs/images/04-parked.png) |

## 실행 방법

1. 개발 환경 세팅: [docs/setup.md](docs/setup.md)
2. Pleos 에뮬레이터를 켜고 앱 설치·실행

   ```
   gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

3. 처음 실행 때 위치 권한 "허용"
4. 정차(P단) 상태에서 "시연용 목적지"를 하나 고른다
5. 앱 오른쪽 끝 창 막대(⋮)를 왼쪽으로 끌어 내비와 나란히 띄운다 (선택)
6. Virtual Console(`adb forward tcp:8088 tcp:8088` 후 http://127.0.0.1:8088/)에서 **Emulation Controls > Gear Selection > D** 로 바꾼다
   - 메인 화면의 "Drive" 버튼은 주행 시뮬레이션까지 켜서 위치를 따로 움직이므로 쓰지 않는다
7. 주행 위치 넣기

   ```
   # Windows PowerShell
   .	ools\demo-drive.ps1              # 입구 도착
   .	ools\demo-drive.ps1 -Scenario pass
   # macOS / Linux
   ./tools/demo-drive.sh arrive
   ./tools/demo-drive.sh pass
   ```

8. 도착 후 Gear Selection을 P로 바꾸면 주차 완료 화면

## 구조

| 경로 | 역할 |
|---|---|
| `guidance/GuidanceEngine.kt` | 안내 판단 로직(순수 코틀린, 단위 테스트 있음) |
| `ui/GuidanceViewModel.kt` | Pleos SDK(위치·기어·내비·음성)와 주행 제한 정보를 엔진에 연결 |
| `ui/GuidanceScreen.kt` | 주행 중 화면 (큰 글씨, 고대비, 주행 중 버튼 숨김) |
| `data/` , `assets/entrances.json` | 입구 데이터. 수집 방법: [docs/data-collection.md](docs/data-collection.md) |
| `check/` | SDK 점검판 (개발용). 결과: [docs/sdk-check.md](docs/sdk-check.md) |

단위 테스트: `gradlew testDebugUnitTest`

## 테스트 환경

- Pleos Connect Emulator v2.1.0 (Android 14, API 34), Automotive AVD
- Android Studio, Kotlin, minSdk 28, Gradle 8.0 이상
- 개발 환경 세팅: [docs/setup.md](docs/setup.md)

## APK

`apk/` 폴더에 release 빌드를 올립니다.
