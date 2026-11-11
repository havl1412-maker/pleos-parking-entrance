# 마지막300M

팀 둠 · 주차장 입구 안내 앱

상가가 밀집한 곳에서 내비가 목적지 근처까지 데려다줘도, 붙어 있는 경사로 중 어느 것이 진짜 주차장 입구인지 헷갈리는 순간을 돕는 Pleos Connect 차량용 앱입니다. 도착 직전에 실제 입구 사진, 옆 건물 입구와 헷갈림 경고, 큰 화살표와 음성으로 "어느 입구로 들어가야 하는지"를 알려 줍니다.

> 개발 중. 앱 이름, 실행 방법, APK 위치는 구현이 진행되면 채웁니다.

## 화면

| 미리 보기 (입구 약 30초 전) | 입구 앞 | 지나쳤을 때 | 주차 완료 |
|---|---|---|---|
| ![](docs/images/01-approach-300m.png) | ![](docs/images/02-at-entrance.png) | ![](docs/images/03-passed.png) | ![](docs/images/04-parked.png) |

| 밤 모드 | 내 차 높이 경고 (다른 방향 입구) | 화면이 내려가 있을 때 도착 알림 |
|---|---|---|
| ![](docs/images/06-night-mode.png) | ![](docs/images/07-height-warning.png) | ![](docs/images/05-arrival-popup.png) |

시연 영상: [docs/demo/demo_parking_entrance.mp4](docs/demo/demo_parking_entrance.mp4) (에뮬레이터 화면, 입구 그림은 예시)

## 하는 일

- **입구 미리 보기**: 입구에 닿기 약 30초 전에 운전자 시점 사진, "오른쪽 · 두 번째 경사로", 경사로 약도를 보여 주고 음성으로 알려 준다. 시점은 속도에 맞춘다(시속 40km 면 약 330m 전, 시속 20km 면 약 170m 전. 150~500m 사이).
- **내비 목적지를 진짜 입구로**: 내비가 건물 앞(지도의 건물 위치)으로 안내하면, 목적지를 우리 데이터의 주차장 입구 좌표로 바꿔 준다. 정차 중에는 "주차장 입구로 바꾸기" 버튼으로 묻고, 그냥 출발하면 자동으로 바꾼 뒤 음성으로 알려 준다. 경로 안내는 순정 내비가 그대로 한다.
- **헷갈리는 입구 경고**: 먼저 나오는 옆 건물 경사로와, 지나치면 바로 나오는 다음 건물 경사로를 따로 경고한다.
- **운영 시간**: 지금 유료 운영 중인지, 무료 개방 시간인지, 문을 닫았는지 구분해서 알려 준다. 곧 닫는 주차장은 닫는 시각을 알려 준다.
- **높이 제한**: 내 차 높이보다 낮은 입구는 들어가기 전에 경고한다.
- **주차 확인과 피드백**: 기어 P 로 주차를 알아채고, 옆 건물에 들어갔을 수 있으면 알려 준다. "아니었어요"라고 답한 입구는 다음 안내 때 한 번 더 확인하라고 알려 준다.
- **낮/밤 사진**: 밤 모드에서는 밤에 찍은 사진을 보여 준다.
- 화면이 내려가 있어도 음성 안내와 도착 알림이 나간다.

이어서 작업하는 사람은 [HANDOFF.md](HANDOFF.md) 를 먼저 읽는다.

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
   .\tools\demo-drive.ps1              # 입구 도착
   .\tools\demo-drive.ps1 -Scenario pass
   # macOS / Linux
   ./tools/demo-drive.sh arrive
   ./tools/demo-drive.sh pass
   ```

8. 도착 후 Gear Selection을 P로 바꾸면 주차 완료 화면

## 구조

| 경로 | 역할 |
|---|---|
| `guidance/GuidanceEngine.kt` | 안내 판단 로직(순수 코틀린, 단위 테스트 있음). 속도에 맞춘 미리 보기 시점 포함 |
| `guidance/RouteAdjust.kt` | 내비 목적지를 주차장 입구로 바꿀지, 어느 입구로 바꿀지 판단 (순수 코틀린, 단위 테스트 있음) |
| `data/OpeningHours.kt` | 요일·시간별 운영 상태(유료 / 무료 개방 / 폐쇄) 판단 (순수 코틀린, 단위 테스트 있음) |
| `data/FeedbackLog.kt` | 주차 후 "맞았어요 / 아니었어요" 기록과 다음 안내 때 주의 표시 판단 (순수 코틀린, 단위 테스트 있음) |
| `ui/GuidanceController.kt` | Pleos SDK(위치·기어·내비·음성)와 주행 제한 정보를 엔진에 연결. 화면이 내려가도 이어지도록 앱 프로세스에 하나만 둔다 |
| `GuidanceKeepAliveService.kt`, `ArrivalNotifier.kt` | 화면이 내려가 있을 때도 위치를 받고, 미리 보기 시점에 도착 알림을 띄운다 |
| `ui/GuidanceScreen.kt` | 주행 중 화면 (큰 글씨, 고대비, 주행 중 버튼 숨김) |
| `data/` , `assets/entrances.json` | 입구 데이터. 수집 방법: [docs/data-collection.md](docs/data-collection.md) |
| `check/` | SDK 점검판 (개발용). 결과: [docs/sdk-check.md](docs/sdk-check.md) |

단위 테스트: `gradlew testDebugUnitTest`

## 테스트 환경

- Pleos Connect Emulator v2.1.0 (Android 14, API 34), Automotive AVD
- Android Studio, Kotlin, minSdk 28, Gradle 8.0 이상
- 개발 환경 세팅: [docs/setup.md](docs/setup.md)

## APK

[apk/둠_마지막300M_release.apk](apk/%EB%91%A0_%EB%A7%88%EC%A7%80%EB%A7%89300M_release.apk) (팀명 둠 · 앱 이름 마지막300M, release 빌드, v3 서명)

설치 (에뮬레이터 사용자 10):

```
adb install apk/둠_마지막300M_release.apk
adb shell pm grant --user 10 com.lastmeter.parking pleos.car.permission.FUSED_LOCATION
adb shell pm grant --user 10 com.lastmeter.parking android.permission.POST_NOTIFICATIONS
```

디버그판이 깔려 있으면 서명이 달라 설치가 실패합니다. `adb uninstall com.lastmeter.parking` 후 설치하세요.

## 다음 단계

빌트인 캠으로 입구 사진을 자동 수집하는 계획 등은 [docs/roadmap.md](docs/roadmap.md) 에 있다.
