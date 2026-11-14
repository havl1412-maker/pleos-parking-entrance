# 인수인계 (2026-11-14 기준)

다른 컴퓨터나 새 작업 세션에서 이어서 작업할 때 먼저 읽는 문서다. AI 도우미로 이어 간다면 "HANDOFF.md 와 docs/ 를 읽고 이어서 하자"고 시키면 된다.

## 1. 지금 상태

- 공모전: 현대자동차그룹 차량용 앱 개발 공모전 (Pleos Connect). 1차 마감 **2026-11-15(일) 23:59**. 필수 = 기획서(8항목) + 전원 서명 동의서, 가산점 = 시연 영상 · GitHub(소스 + APK).
- 앱: 상가 밀집 지역에서 붙어 있는 경사로 중 진짜 주차장 입구를 알려 주는 Pleos Connect 앱. 에뮬레이터 v2.1.0 에서 전체 흐름이 동작한다.
- 1차 제출물: 접수양식(기획서 8항목 + 전원 서명 동의서), release APK [apk/둠_마지막300M_release.apk](../apk/), GitHub Release v1.0.0.
- 시연 영상(11/13): [1편 내비 목적지 바로잡기 → 입구 안내 → 주차](demo/demo_1_navi_fix_and_entrance.mp4), [2편 운영 시간 · 지나침 · 무료 개방](demo/demo_2_hours_and_passed.mp4). 11/10 전체 흐름판 [demo_parking_entrance.mp4](demo/demo_parking_entrance.mp4) 도 남겨 두었다. 모두 에뮬레이터 화면이고 입구 그림은 예시 그림이다.
- 입구 데이터(`dev-001` ~ `dev-003`)는 시연용 예시이며 실제 장소가 아니다. 실제 장소 조사는 아래 4절의 다음 단계다.

## 2. 되는 것

| 기능 | 위치 |
|---|---|
| 미리 보기 → 입구 앞 → 지나침 → 주차 완료(P단) | `guidance/GuidanceEngine.kt` (순수 코틀린). 단위 테스트는 엔진·문구·운영 시간·피드백·데이터 읽기·목적지 바꾸기 합쳐 71개 |
| 속도에 맞춘 미리 보기 시점: 입구에 닿기 약 30초 전(속도 × 30초, 150~500m). 속도를 모르면 300m | `GuidanceEngine.previewDistanceM`. 속도는 최근 5초 위치 변화로 구한다(SDK 위치에 속도 칸이 없음) |
| 운영 시간 안내: 유료 운영 / 무료 개방 / 폐쇄를 구분. 닫았으면 화면 경고 + 음성, 무료 개방이면 "언제부터 유료", 2시간 안에 닫으면 닫는 시각 | `data/OpeningHours.kt`, `entrances.json` 의 `schedule`·`holidays`, `SpeechTexts.accessLine` |
| 내비 목적지 바로잡기: 내비 목적지가 이 건물(입구나 건물 위치에서 100m 안)인데 입구에서 25m 넘게 떨어져 있으면 목적지를 입구 좌표로 바꿔 요청. 정차 중에는 카드 버튼("주차장 입구로 바꾸기 / 그대로 두기")으로 묻고, 답하지 않고 출발하거나(주행 제한이 켜짐) 주행 중에 목적지를 정하면 자동으로 바꾸고 음성 안내. 입구가 여럿이면 지금 위치에서 들어가기 좋은 입구 | `guidance/RouteAdjust.kt`, `GuidanceController.considerRouteAdjust`, 권한 `NAVI_ROUTE_SEARCH` |
| 주차를 마치고 다시 출발하면(P→D) 입구 앞을 지나도 안내를 다시 하지 않음. 600m 넘게 벗어나거나 목적지가 바뀌면 다시 안내. 옆 건물일 수 있는 곳에 주차했으면 다시 찾으러 나가는 것으로 보고 바로 다시 안내 | `GuidanceEngine` 의 `finished` |
| 데이터의 진입 방향과 반대쪽(120도 넘게 다름)에서 오면 "오른쪽 · 두 번째"·약도·순서가 든 경고를 빼고 "다른 방향에서 오는 중 · 사진의 간판을 보고 찾으세요"로 안내. 사진에도 "평소 방향에서 찍은 사진" 표시 | `GuidanceEngine.isOtherDirection`, `GuidanceState.*.otherDirection` |
| `entrances.json` 에 틀린 곳이 있어도 그 입구·시설만 빼고 나머지는 읽음. 무엇이 왜 빠졌는지 대기 화면 빨간 카드와 Logcat(`Guidance`)에 보여 줌. 사진 파일 이름이 틀려도 알려 줌 | `data/EntranceRepository.kt` |
| 주차 후 피드백 저장: "아니었어요"면 이유(다른 경사로 / 닫혀 있었음 / 사진·설명과 다름)를 묻고, 다음에 같은 입구를 안내할 때 화면 + 음성으로 알림 | `data/FeedbackLog.kt` (이 차 안에만 저장, 최근 90일) |
| Pleos 색·글꼴(Asta Sans), 낮/밤 자동 전환, 한 칸/두 칸 배치 | `ui/PleosTheme.kt`, `ui/GuidanceScreen.kt` |
| 경사로 약도(앞쪽 옆 건물 ✕ · 여기 ✓ · 뒤쪽 다음 건물 ✕), 높이 제한 표시, 내 차 높이 경고(화면 + 음성) | `ui/GuidanceScreen.kt`, `ui/GuidanceController.kt` |
| 입구가 여러 개일 때 달리는 방향에 맞는 입구 고르기 | `GuidanceEngine.pickEntrance` |
| 낮/밤 사진 구분 | `entrances.json` 의 `photoFarNight`, `photoNearNight` |
| 화면이 내려가 있어도 음성 안내 + 도착 알림 팝업 | `GuidanceKeepAliveService.kt`, `ArrivalNotifier.kt` |
| SDK 점검판(개발용) | `check/` |

SDK 별 확인 결과와 함정은 [docs/sdk-check.md](sdk-check.md) 에 있다. 특히 8절(화면이 내려가 있을 때)을 읽을 것.

## 3. 알아 둘 제약

- 앱이 스스로 내비 옆 칸에 뜨는 방법은 없다(시스템이 막음). 미리 창을 나눠 두거나, 도착 알림을 눌러야 한다.
- 공개 Pleos SDK 에 카메라 API 가 없다. 빌트인 캠은 계획으로만 둔다 → [docs/roadmap.md](roadmap.md).
- Pleos SDK 디컴파일·역분석 금지(라이선스). 필드는 문서·샘플·실행 로그로만 확인한다.
- 내비가 아닌 앱은 "몇 m 앞 우회전" 같은 경로 안내를 하면 안 된다(단일 경로 정책).
- 블라인드 심사: 저장소·README·커밋·영상·기획서에 실명·학교·동아리 이름을 쓰지 않는다.
- Virtual Console 의 큰 "Drive" 버튼은 누르지 않는다(위치가 저절로 움직인다). 기어는 Emulation Controls > Gear Selection 으로 바꾼다.
- 백그라운드 유지 서비스는 **앱 화면이 보일 때** 켠다. 내비에서 목적지를 정하면 우리 앱은 뒤로 가는데, 안드로이드는 화면 뒤에 있는 앱의 포그라운드 서비스 시작을 막는다(`startForegroundService() not allowed`). 그래서 앱을 한 번 열면 서비스가 켜지고, 목적지가 없어져도 끄지 않는다. 알림 줄에 "주차장 입구 안내 대기 중"이 계속 보이는 것은 정상이다.
- 11/11 확인: 내비만 보이는 상태(우리 앱은 뒤)에서 내비 목적지 → 자동으로 안내 대상 선택 → 300m 전 음성, 입구 앞 옆 건물 경고 음성, 내비 위 도착 알림까지 동작.
- 11/11 밤 확인: 처음 실행하면 알림 → 위치 순서로 권한 창이 뜬다. 다만 Pleos 위치 권한 창의 문구가 "알 수 없는 작업 실행하도록 허용하시겠습니까?"로 나온다(권한에 시스템용 설명이 없음). 사용자가 거절하기 쉬우니 다음 버전에서 권한을 묻기 전에 "주차장 입구를 알려 드리려면 위치가 필요해요" 같은 설명 화면을 먼저 보여 준다(4절). 앱을 강제 종료한 뒤의 동작은 아직 확인하지 못했다(안드로이드 규칙상 강제 종료하면 다시 열 때까지 멈춘다).
- 11/11 밤 코드 리뷰로 고친 것: 내비가 목적지를 잠깐 비울 때 안내 상태가 초기화되던 것(5초 기다리고, 입구 근처 안내 중이거나 목적지 바꾸기를 기다리는 중이면 유지), 입구 전 신호 대기 중 P 를 주차로 보던 것(입구 80m 안에 와 본 뒤에만 주차), 멀리서 정한 입구가 반대 방향이면 다른 입구로 다시 고르기, 내비를 보낸 입구로 화면 안내 맞추기, 속도 계산(겹쳐 온 위치·끊긴 뒤 예전 속도), 무료 개방 중 곧 닫음 안내, 높이 제한에 걸리거나 문을 닫았으면 입구 앞에서 "들어가세요" 대신 경고, "바꿨어요"는 내비가 받아들인 뒤에 말하기.
- 옆길에서 꺾어 들어오는 중(진입 방향과 약 90도)은 반대쪽으로 보지 않고 평소처럼 안내한다. 멀리서는 옆길에 있다가 입구 길로 꺾어 들어오는 경우가 많아서다. 그래서 수직 방향에서 그대로 들어오는 입구는 순서가 틀릴 수 있다. 이런 입구는 `entrances.json` 에 진입 방향마다 입구 항목을 따로 적는다.
- 개발용 SDK 점검판 버튼은 debug 빌드에서만 보인다(release 에서는 숨김). 점검판의 "시험 목적지 요청·경로 취소"는 뺐다.
- `NAVI_ROUTE_SEARCH` 권한은 11/11 에 한 번 뺐다가, 내비 목적지 바로잡기 기능에 쓰려고 다시 넣었다. 경로 안내는 순정 내비가 하고 우리는 목적지 좌표만 바꾸므로 단일 경로 정책에 맞는다고 판단했다. 사무국 문의 목록에 "서드파티 앱이 목적지를 바꿔도 되는지, 자동으로 바꿔도 되는지"를 넣을 것.
- 내비는 목적지를 바꿀 때 잠깐 목적지를 비웠다가(좌표 0,0) 새 목적지를 알려 준다. 그때마다 안내 대상이 잠깐 풀렸다가 다시 잡힌다. 출발 전에는 문제없지만, 입구 바로 앞에서 목적지를 바꾸면 미리 보기 음성이 다시 나올 수 있다.
- 전조등 연동(하향등이 켜져 있으면 밤 사진)은 11/11 에 만들어서 에뮬레이터로 확인했지만 뺐다. 화면 밤 모드가 이미 자동으로 바뀌어서 얻는 것이 적고, 권한(`CAR_LIGHTS`)을 하나 더 선언해야 했기 때문이다. 확인한 내용은 [docs/sdk-check.md](sdk-check.md) 10절에 남겨 두었다.
- 와이퍼 연동(비가 오면 미리 보기를 10초 더 일찍)은 엔진 쪽(`GuidanceEngine.extraLeadSec`, 테스트 있음)만 만들어 두고 차량 신호와는 잇지 않았다. Wiper API 가 첫 인자로 받는 `WindowArea` 값을 만드는 방법을 공식 문서에서 확인하지 못했기 때문이다. 확인되면 `GuidanceController` 에서 와이퍼가 켜졌을 때 `extraLeadSec = 10.0` 으로 두고 권한을 선언하면 된다 → [docs/sdk-check.md](sdk-check.md) 10절.
- 공휴일: `entrances.json` 의 `holidays` 에 해마다 같은 날(1/1, 3/1, 5/5, 6/6, 8/15, 10/3, 10/9, 12/25)만 넣어 두었다. 설·추석·대체 공휴일은 해마다 달라서 직접 넣어야 한다.
- 에뮬레이터를 켠 채 gradle 빌드를 돌리면 에뮬레이터가 꺼지거나, 살아 있어도 위치가 앱에 안 들어올 때가 있다(11/11: 내비 목적지·기어는 되는데 `위치` 로그가 0건). 에뮬레이터를 다시 켜면 된다. 빌드 후에는 `gradlew --stop` 으로 데몬을 내려 메모리를 비운다.

## 4. 다음 단계

1차 제출에 필요한 것(기획서 8항목, 전원 서명 동의서, release APK, 시연 영상, GitHub Release)은 모두 마쳤다. 아래는 그 이후 할 일이다(우선순위 순).

1. **실제 장소 조사**(3~5곳) → `entrances.json` 과 사진 교체. 방법: [docs/data-collection.md](data-collection.md). 사진은 **운전자 시점(비스듬히)** 으로 찍는다. 예시 그림(`tools/draw_example_entrance.py`)도 운전자 시점으로 그려 두었다.
2. 실제 사진으로 시연 영상 다시 만들기 (`tools/video/make_demo_video.py`, 녹화 방법은 아래 6절).
3. 위치 권한을 묻기 전에 이유를 설명하는 화면 넣기(3절의 권한 창 문구 문제).
4. 사무국 문의: 앱이 내비 옆 칸에 스스로 뜨는 방법, 서드파티 앱의 목적지 변경 허용 범위, 차량 속도 신호, 빌트인 캠 접근, `getGear` 대체 API, Wiper API 의 `WindowArea`.
5. 공공 주차장 입구 데이터 문의: [docs/seoul-data-inquiry.md](seoul-data-inquiry.md) 문구 사용.

release APK 다시 만들기:
- 빌드: `./gradlew assembleRelease` (약 11분, 대부분 lintVital 단계). 결과 `app/build/outputs/apk/release/app-release.apk` 를 `apk/둠_마지막300M_release.apk` 로 덮어쓴다.
- 서명 키는 저장소 밖 `C:\pleos\keys\release.jks` + `keystore.properties` 에 있다. 저장소 루트에 `keystore.properties` 를 복사해 두면 서명된다(깃에 안 올라감). **키를 잃어버리면 앱 업데이트가 불가능**하므로 따로 백업하고, 다른 컴퓨터에서 release 를 만들 때도 같은 키를 쓴다.

## 5. 새 컴퓨터 세팅

자세한 순서는 [docs/setup.md](setup.md). 요약:

1. Git, Android Studio(내장 JDK 사용) 설치 후 이 저장소를 받는다.
2. SDK Manager 에 Pleos 업데이트 사이트를 추가하고 API 34 Pleos Connect 시스템 이미지를 설치한다(약 4GB, 라이선스 동의 필요). Android SDK Platform 34 와 37.2 도 필요하다(`compileSdk 37.2`, `targetSdk 34`).
3. AVD: Automotive > Pleos Connect v2.1.0, Cold boot, RAM 4GB.
4. 저장소 루트에 `local.properties` 를 만든다. Windows 예: `sdk.dir=C\:\\Users\\<이름>\\AppData\\Local\\Android\\Sdk` (역슬래시 두 번).
5. `./gradlew assembleDebug testDebugUnitTest` 로 빌드와 테스트를 확인한다.
6. 에뮬레이터를 켠 뒤 설치하고, 아래 권한을 준다(에뮬레이터 사용자는 10번이다).

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant --user 10 com.lastmeter.parking pleos.car.permission.FUSED_LOCATION
adb shell pm grant --user 10 com.lastmeter.parking android.permission.POST_NOTIFICATIONS
adb forward tcp:8088 tcp:8088
```

7. `adb` 가 안 찾아지면 `%LOCALAPPDATA%\Android\Sdk\platform-tools` 를 사용자 PATH 에 넣는다. `tools/demo-drive.ps1` 도 `adb` 를 PATH 에서 찾는다.
8. 내비가 "서비스 연결 중"에서 멈추면 에뮬레이터가 DNS 를 못 찾는 것이다(11/11 노트북에서 발생, IP 로는 인터넷이 됨). `tools/start-emulator.ps1` 로 켜면 DNS 서버를 지정해서 켠다. Device Manager 의 실행 버튼으로 켜면 이 옵션이 빠진다.

## 6. 시연 돌리는 법

1. 기어 P 에서 앱을 열고 "개발용 가상 상가"를 고른다(주행 중에는 이 버튼이 숨겨진다).
   - 내비 목적지 바로잡기 시연(debug 빌드): 위치를 입구에서 멀리 두고(예: `adb emu geo fix 127.09468 37.405`) "시연: 내비 목적지를 개발용 가상 상가 건물 앞으로"를 누른다 → 앱에 "내비가 건물 앞으로 안내하고 있어요" 카드 → "주차장 입구로 바꾸기"를 누르거나 기어를 D 로 바꾸면 내비 목적지가 "개발용 가상 상가 주차장 입구"로 바뀐다. 내비가 "서비스 연결 중"이면 에뮬레이터를 `start-emulator.ps1` 로 다시 켠다.
   - 운영 시간 시연: "개발용 공영 주차장"(평일·토 낮 유료, 그 외 무료 개방)이나 "개발용 야간 폐쇄 빌딩"(07~22시, 밤에는 폐쇄)을 고르고, 아래 "시연용 시각" 버튼을 눌러 평일 낮 → 평일 밤 9시 30분 → 평일 밤 11시 → 일요일 낮으로 바꾼다.
   - 속도에 따른 미리 보기 시점: `demo-drive.ps1 -SpeedMps 8`(시속 29km)이면 약 240m 전, `-SpeedMps 14`(시속 50km)이면 약 420m 전에 뜬다(스크립트가 조금 느리게 돌아 실제로는 이보다 가깝게 뜬다).
2. 창 막대(⋮)를 끌어 내비와 반반으로 나눈다.
3. 기어 D 로 바꾸고 `./tools/demo-drive.sh arrive` (Windows PowerShell 은 `tools/demo-drive.ps1`). 지나침은 `pass`.
4. 도착 후 기어 P → 주차 완료 화면.
5. 밤 모드: `adb shell cmd uimode night yes` / `no`.
6. 영상용 녹화: `adb shell screenrecord` 로 찍고, Logcat `Guidance` 태그의 "음성" 시각을 `make_demo_video.py` 의 `CLIPS` 표에 옮긴다. ffmpeg 가 필요하다.
