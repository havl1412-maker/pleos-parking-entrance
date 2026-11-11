# 인수인계 (2026-11-10 기준)

다른 컴퓨터나 새 작업 세션에서 이어서 작업할 때 먼저 읽는 문서다. AI 도우미로 이어 간다면 "HANDOFF.md 와 docs/ 를 읽고 이어서 하자"고 시키면 된다.

## 1. 지금 상태

- 공모전: 현대자동차그룹 차량용 앱 개발 공모전 (Pleos Connect). 1차 마감 **2026-11-15(일) 23:59**. 필수 = 기획서(8항목) + 전원 서명 동의서, 가산점 = 시연 영상 · GitHub(소스 + APK).
- 앱: 상가 밀집 지역에서 붙어 있는 경사로 중 진짜 주차장 입구를 알려 주는 Pleos Connect 앱. 에뮬레이터 v2.1.0 에서 전체 흐름이 동작한다.
- 시연 영상: [docs/demo/demo_parking_entrance.mp4](docs/demo/demo_parking_entrance.mp4) (3분 18초, 11/10 화면 기준). 입구 그림은 예시 그림이고 에뮬레이터 화면이다.
- 입구 데이터는 아직 가짜(`dev-001`, 실제 장소 아님)다.

## 2. 되는 것

| 기능 | 위치 |
|---|---|
| 300m 전 미리 보기 → 입구 앞 → 지나침 → 주차 완료(P단) | `guidance/GuidanceEngine.kt` (순수 코틀린, 단위 테스트 9개) |
| Pleos 색·글꼴(Asta Sans), 낮/밤 자동 전환, 한 칸/두 칸 배치 | `ui/PleosTheme.kt`, `ui/GuidanceScreen.kt` |
| 경사로 약도, 높이 제한 표시, 내 차 높이 경고(화면 + 음성) | `ui/GuidanceScreen.kt`, `ui/GuidanceController.kt` |
| 입구가 여러 개일 때 달리는 방향에 맞는 입구 고르기 | `GuidanceEngine.pickEntrance` |
| 낮/밤 사진 구분 | `entrances.json` 의 `photoFarNight`, `photoNearNight` |
| 화면이 내려가 있어도 음성 안내 + 도착 알림 팝업 | `GuidanceKeepAliveService.kt`, `ArrivalNotifier.kt` |
| SDK 점검판(개발용) | `check/` |

SDK 별 확인 결과와 함정은 [docs/sdk-check.md](docs/sdk-check.md) 에 있다. 특히 8절(화면이 내려가 있을 때)을 읽을 것.

## 3. 알아 둘 제약

- 앱이 스스로 내비 옆 칸에 뜨는 방법은 없다(시스템이 막음). 미리 창을 나눠 두거나, 도착 알림을 눌러야 한다.
- 공개 Pleos SDK 에 카메라 API 가 없다. 빌트인 캠은 계획으로만 둔다 → [docs/roadmap.md](docs/roadmap.md).
- Pleos SDK 디컴파일·역분석 금지(라이선스). 필드는 문서·샘플·실행 로그로만 확인한다.
- 내비가 아닌 앱은 "몇 m 앞 우회전" 같은 경로 안내를 하면 안 된다(단일 경로 정책).
- 블라인드 심사: 저장소·README·커밋·영상·기획서에 실명·학교·동아리 이름을 쓰지 않는다.
- Virtual Console 의 큰 "Drive" 버튼은 누르지 않는다(위치가 저절로 움직인다). 기어는 Emulation Controls > Gear Selection 으로 바꾼다.
- 백그라운드 유지 서비스는 **앱 화면이 보일 때** 켠다. 내비에서 목적지를 정하면 우리 앱은 뒤로 가는데, 안드로이드는 화면 뒤에 있는 앱의 포그라운드 서비스 시작을 막는다(`startForegroundService() not allowed`). 그래서 앱을 한 번 열면 서비스가 켜지고, 목적지가 없어져도 끄지 않는다. 알림 줄에 "주차장 입구 안내 대기 중"이 계속 보이는 것은 정상이다.
- 11/11 확인: 내비만 보이는 상태(우리 앱은 뒤)에서 내비 목적지 → 자동으로 안내 대상 선택 → 300m 전 음성, 입구 앞 옆 건물 경고 음성, 내비 위 도착 알림까지 동작.
- 알림 권한 요청 창이 실제로 뜨는지, 앱을 강제 종료한 뒤에도 안내가 이어지는지는 아직 직접 확인하지 못했다.

## 4. 남은 일 (우선순위 순)

1. **기획서 8항목 작성** (필수 제출물). 넣을 것: 구글 지도의 도착지 입구 강조 기능과의 차별점(몇 번째 경사로인지, 옆 건물 경고, 기어 P 연동), 눈에 띄는 표지를 쓰는 안내가 화면 보는 시간과 실수를 줄인다는 연구(May, Ross & Osman 2005), 빌트인 캠 확장 계획.
2. **전원 서명 동의서** 준비.
3. **실제 장소 조사**(3~5곳) → `entrances.json` 과 사진 교체. 방법: [docs/data-collection.md](docs/data-collection.md). 사진은 **운전자 시점(비스듬히)** 으로 찍는다(11/11 변경, 정면 사진 아님). 지금 예시 그림(`tools/draw_example_entrance.py`)은 아직 정면 구도라 다시 그려야 한다(Pillow 필요).
4. 실제 사진으로 시연 영상 다시 만들기 (`tools/video/make_demo_video.py`, 녹화 방법은 아래 6절).
5. ~~release APK 를 `apk/` 에 넣기~~ → 11/11 임시 파일명 `apk/Team_ParkingEntrance_release.apk` 로 올림(v3 서명). 팀명·앱 이름이 정해지면 `strings.xml` 의 `app_name` 을 바꾸고 다시 빌드해서 `팀명_앱이름_release.apk` 로 바꾼다.
   - 빌드: `./gradlew assembleRelease` (약 11분, 대부분 lintVital 단계). 결과는 `app/build/outputs/apk/release/app-release.apk`.
   - 서명 키는 저장소 밖에 있다(노트북 `C:\pleos\keys\release.jks` + `keystore.properties`). 저장소 루트에 `keystore.properties` 를 복사해 두면 서명된다(깃에 안 올라감). **키를 잃어버리면 앱 업데이트가 불가능**하므로 USB 등에 따로 백업하고, 다른 컴퓨터에서 release 를 만들 때도 같은 키를 쓴다.
6. 사무국 문의: 앱이 내비 옆 칸에 스스로 뜨는 방법, 차량 속도 신호, 빌트인 캠 접근, `getGear` 대체 API.
7. 공공 주차장 입구 데이터 문의: [docs/seoul-data-inquiry.md](docs/seoul-data-inquiry.md) 문구 사용.

## 5. 새 컴퓨터 세팅

자세한 순서는 [docs/setup.md](docs/setup.md). 요약:

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
2. 창 막대(⋮)를 끌어 내비와 반반으로 나눈다.
3. 기어 D 로 바꾸고 `./tools/demo-drive.sh arrive` (Windows PowerShell 은 `tools/demo-drive.ps1`). 지나침은 `pass`.
4. 도착 후 기어 P → 주차 완료 화면.
5. 밤 모드: `adb shell cmd uimode night yes` / `no`.
6. 영상용 녹화: `adb shell screenrecord` 로 찍고, Logcat `Guidance` 태그의 "음성" 시각을 `make_demo_video.py` 의 `CLIPS` 표에 옮긴다. ffmpeg 가 필요하다.
