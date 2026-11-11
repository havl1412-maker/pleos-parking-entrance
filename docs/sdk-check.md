# SDK 점검 결과 (2026-11-08)

Pleos Connect 에뮬레이터 v2.1.0에서 앱의 "SDK 점검판" 화면으로 확인한 결과. SDK 필드는 디컴파일하지 않고, 콜백 객체를 실행 중에 로그로 찍어서 확인했다.

## 요약

| 항목 | 결과 | 핵심 |
|---|---|---|
| 내비 연동 (NaviHelper 3.0.5) | 동작 | 목적지 이름·좌표·남은 거리를 받을 수 있음. 앱에서 목적지 요청도 됨 |
| 위치 (FusedLocation 1.1.3) | 동작 | 약 1초마다 갱신. 실행 중 권한 요청 필요 |
| 근접 알림 | 동작 | 반경 300m ENTER/EXIT가 1~3초 안에 옴 |
| 기어 (Vehicle 2.3.2) | 동작 | GEAR_PARK / GEAR_DRIVE. 단, deprecated API |
| 음성 (TextToSpeech 3.3.1) | 동작 | 한국어로 들림. 에뮬레이터에서는 소리가 끊김 |
| 주행 제한 (CarUxRestrictions) | 동작 | D단 전환 시 제한 상태로 바뀜 |

## 1. 내비 연동

- `NaviHelper(context).initialize()` 후 `addListener` → 즉시 사용 가능.
- `onDestinationInfo` 필드: `latitude, longitude, displayLatitude, displayLongitude, name, address, remainDistance, remainTime, tollFare, poiId, poiSubId, poiProvider`
  - **remainDistance(m), remainTime** 이 있어 "목적지까지 300m" 판단에 쓸 수 있다.
- `onDrivingInfo` → `DrivingInfo(destination = DrivingState(name, duration, distance, ...), waypoints)`. 안내 중 남은 거리가 계속 들어온다.
- `onTBTInfo` → `TBTInfo(type = TURN_ARRIVE, distance, description = "도착")`
- `onCurrentLocationInfo` → `latitude, longitude, heading, address, routeId, currentRouteIndex`
- `requestRoute(RouteInfo)` 호출 시 순정 내비가 안내를 시작: `onRouteStarted(duration, distance, name, ...)`, `onNavigationStatus ROUTE_DRIVE`. `cancelRoute()` 시 `onRouteCancelled`, `FREE_DRIVE`.

**주의 1. 에뮬레이터 내비는 실제 경로를 계산하지 않는다.** 서울 강남 좌표(약 10km 떨어진 곳)를 보냈는데 목적지가 현재 위치(성남 시흥동) 옆 50m로 바뀌어 안내됐다. 지도도 흰 화면이다. 그래서 에뮬레이터 시연에서는 내비의 남은 거리 대신, 앱이 가진 입구 좌표와 위치 SDK 근접 알림으로 300m 시점을 판단해야 한다.

**주의 2. `requestRoute`를 부르면 순정 내비가 화면 앞으로 나오고 우리 앱은 가려진다.** 안내 중에 우리 카드(사진·화살표)를 어떻게 보여 줄지 정해야 한다. 화면 오른쪽 끝의 분할 손잡이(⋮)로 내비와 함께 띄울 수 있는지 다음에 확인한다.

## 2·3. 위치와 근접 알림

- 권한 `pleos.car.permission.FUSED_LOCATION`은 설치만으로 허용되지 않는다(`granted=false`). 실행 중 `RequestPermission`으로 요청하면 "허용 / 허용 안 함" 창이 뜬다.
- `LocationData` 필드: `latitude, longitude, altitude, heading, confidence, timestampMillis`. **속도·정확도·위성 수 필드는 없다.**
- 갱신 간격 약 1초. 새 좌표를 넣지 않아도 같은 좌표로 1초마다 계속 온다. → 에뮬레이터에서는 "갱신이 끊기는 것"으로 지하 진입을 흉내 낼 수 없다.
- 첫 위치는 에뮬레이터 기본값(미국 좌표)이 올 수 있다. `adb emu geo fix <경도> <위도>`로 넣은 뒤부터 정상.
- 근접 알림: `ProximityAlertRequest(latitude, longitude, radiusMeters = 300f, transitionTypes = {ENTER, EXIT})` → `onRegistered(id)` → 좌표를 반경 밖·안으로 옮기면 `onTransition(EXIT/ENTER)`.

## 4. 기어

- `Vehicle(context).initialize()` 후 `getGear().getCurrentGearSelectionState(onComplete, onFailed)` → `GEAR_PARK`, `GEAR_DRIVE`.
- 권한 `pleos.car.permission.CAR_DRIVE` (설치 시 자동 허용).
- `getGear()`는 deprecated 표시가 있다. 공식 문서의 Vehicle 도메인 목록에도 Gear가 없다. 실서비스 심사 때 문제 될 수 있으니 사무국 문의 항목에 넣는다. 대안은 AAOS 표준 `GEAR_SELECTION`.
- 변화 감지 리스너는 아직 시험하지 않았다(지금은 조회만).

## 5. 음성

- `TextToSpeech(context, Mode.ON_DEVICE).initialize(listener)` → `onSuccess` → `setLocale(Locale.KOREA)` → `speak(text, true)` 호출 오류 없음.
- 실제 소리 확인(2026-11-08, 사람이 들음): 한국어로 나오지만 중간중간 끊긴다. 에뮬레이터의 PC 오디오 출력 문제일 가능성이 높다(실차와 무관). 시연 영상은 화면 녹화에 음성을 따로 입히는 방법도 고려.

## 6. 주행 제한

- `Car.createCar` → `CarUxRestrictionsManager`. P단: 제한 없음(`active=0x0`). D단으로 바꾸면 즉시 `isRequiresDistractionOptimization=true`, `active=0x50` (NO_VIDEO + NO_SETUP), 최대 글자 수 120.
- 매니페스트에 `distractionOptimized=true`를 선언했으므로 D단에서도 화면이 유지됐다.

## 설계에 반영할 점

1. 300m 트리거는 **입구 좌표 + 근접 알림**으로 한다. 실차에서는 내비 `remainDistance`를 보조로 쓴다.
2. 내비 안내 중 우리 카드를 띄우는 방법(분할 화면 등)을 다음에 확인한다. 가장 큰 미정 사항.
3. 지하 진입 감지는 에뮬레이터로 검증할 수 없다. 시연에서는 녹화 궤적 재생으로 보여 주고, 기획서에는 실차 검증 계획으로 쓴다.
4. 기어는 deprecated API라 사무국 문의 목록에 추가한다.

## 7. 내비와 한 화면에 띄우기 (2026-11-08 추가)

결론: **내비 안내 중에도 우리 앱을 옆 칸에 띄울 수 있다.** 다만 분할은 사용자가 만든다.

- Pleos 화면 구조: 왼쪽 고정 "Driving view"(앱이 가리거나 끌 수 없음) + 오른쪽 앱 영역. 앱 영역에는 앱을 최대 2개까지 나란히 둘 수 있다(1:1, 2:1). 기본은 첫 칸 내비, 둘째 칸 미디어. 출처: https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model , https://document.pleos.ai/en/docs/connect/guide/docs-design/getting-started/window
- 에뮬레이터 실험:
  - 우리 앱을 실행하면 앱 영역 전체를 차지하고 내비는 뒤로 숨는다.
  - 앱 오른쪽 끝의 창 막대(⋮)를 왼쪽으로 끌면 1:1로 나뉜다. 왼쪽 칸 우리 앱, 오른쪽 칸 내비.
  - 분할 상태에서 앱이 `requestRoute`를 보내도 분할이 유지되고, 내비는 자기 칸에서 안내를 시작한다. 화면: `docs/images/split-with-navi.png`
  - 창 막대를 탭만 해서는 분할되지 않았다.
  - 매니페스트에 `screenOrientation="portrait"`를 넣으면 반쪽 창이 아니라 양옆이 검게 빈 화면이 됐다. 그래서 넣지 않는다.
- 앱이 스스로 분할을 요청하는 API는 문서에서 찾지 못했다. 오버레이(떠 있는 창), 서드파티 위젯, PiP에 대한 문서도 없다.
- 주행 중 전체 화면 팝업은 금지("Prohibit full-screen pop-ups while driving").

설계 반영:
1. 입구 카드 화면은 **반쪽 칸(가로 약 850px, 세로 약 1200px)** 에 맞춰 만든다.
2. 사용 방법은 "출발 전 정차 중에 우리 앱을 내비 옆 칸에 띄워 두기"로 안내한다. 기본 둘째 칸이 미디어라, 미디어 대신 우리 앱을 두는 셈이다.
3. 우리 앱이 화면에 없을 때도 음성 안내는 뒤에서 나가게 한다(화면은 못 띄워도 소리는 가능).
4. 음성 안내 문구는 화살표·턴 안내가 아니라 "편의점 옆 경사로" 같은 입구 설명으로 한다. 내비 외 앱의 경로 안내는 단일 경로 정책에 걸릴 수 있다.

## 8. 화면이 내려가 있을 때 (내비만 보일 때) 동작 — 2026-11-10 에뮬레이터 확인

- 앱 화면이 내려가면 시스템이 프로세스를 멈춘다(`isFrozen=true`). 위치 콜백도 끊긴다. → 포그라운드 서비스(`GuidanceKeepAliveService`, specialUse)를 켜 두면 백그라운드에서도 위치를 계속 받고 음성 안내가 나간다.
- 앱이 스스로 화면을 올리는 것은 막힌다. 포그라운드 서비스 상태에서 `startActivity` → `Background activity launch blocked (BAL_BLOCK)`.
- 대신 알림은 내비 위에 팝업으로 뜬다(중요도 HIGH, `CATEGORY_NAVIGATION`). 누르면 앱이 열린다. 단, 알림 권한(`POST_NOTIFICATIONS`)을 사용자 10에게 받아야 한다.
- 알림을 눌러 열린 앱은 직전 앱과 같은 방향으로 뜬다. 내비가 두 칸(가로형)이었으면 우리 앱도 두 칸으로 떠서 내비를 가린다. 내비와 나란히 보려면 미리 창을 나눠 둬야 한다(창 막대 드래그). 근거: https://document.pleos.ai/en/docs/connect/guide/docs-design/getting-started/window
- 색·글꼴 규칙 출처: https://document.pleos.ai/en/docs/connect/guide/docs-design/foundations/colors , https://document.pleos.ai/en/docs/connect/guide/docs-design/foundations/typography

## 9. 화면 뒤에서 목적지가 정해질 때 — 2026-11-11 에뮬레이터 확인

- 내비에서 목적지를 정하면 순정 내비가 앞으로 나오고 우리 앱은 뒤로 간다. 이때 `onDestinationInfo` 를 받아 포그라운드 서비스를 켜려 하면 `startForegroundService() not allowed due to mAllowStartForeground false` 로 막힌다(안드로이드 12+ 백그라운드 시작 제한).
- 해결: 앱 화면이 보일 때(`onStart`) 서비스를 켜 두고, 목적지가 없어져도 끄지 않는다(`GuidanceController.ensureKeepAlive`).
- 결과: 우리 앱이 뒤에 있는 상태에서 내비 목적지 → 안내 대상 자동 선택 → 300m 전 음성 → 입구 앞 옆 건물 경고 음성 → 내비 위 도착 알림까지 동작했다. 프로세스는 `curProcState=4`(포그라운드 서비스), `isFrozen=false`.
- 함께 고친 것: `GuidanceController` 의 `init` 블록이 `naviListener` 선언보다 앞에 있어 `NaviHelper.addListener(null)` 로 내비 연동이 조용히 실패하고 있었다(11/10 구조 변경 때 생김). `init` 을 클래스 끝으로 옮겼다.
