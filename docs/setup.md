# 개발 환경 세팅 (팀원용)

공식 가이드: https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator

## 1. Android Studio 설치
최신 안정판 설치 (https://developer.android.com/studio). 내장 JDK를 그대로 쓰면 된다.

## 2. Pleos 에뮬레이터 이미지
Android Studio > Tools > SDK Manager > SDK Update Sites 탭 > `+`

- 이름: Pleos Connect System Image
- Windows/Linux: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
- Mac(Apple Silicon): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`

SDK Platforms 탭에서 Show Package Details 체크 > API 34의 Pleos Connect 시스템 이미지 설치 (약 4GB) > Android Studio 재시작.
설치할 때 Pleos Connect SDK License(42dot) 동의가 필요하다. SDK 디컴파일·역분석은 라이선스상 금지.

## 3. AVD 만들기
Device Manager > Create device > Automotive > Pleos Connect v2.1.0 > Advanced Settings에서 Default boot: Cold, RAM 4GB > API 34 Pleos Connect v2.1.0 APIs 선택.

## 4. Virtual Console
에뮬레이터 부팅 후 터미널에서:

```
adb forward tcp:8088 tcp:8088
```

브라우저에서 http://127.0.0.1:8088/ 접속 → 기어, 도어, 속도 등을 가상으로 변경.

## 주의
- 이전 Pleos 에뮬레이터가 있으면 지우고 설치
- minSdk는 28 이상 (보안 요구사항)
- 에뮬레이터 부팅이 끝난 뒤 앱 실행
- 블라인드 심사: README, 커밋 메시지, 코드 주석에 실명·학교·동아리 이름을 쓰지 않는다

## 문제 해결
- **내비가 "서비스 연결 중"에서 안 넘어감**: 에뮬레이터가 DNS를 못 찾는 경우다(`adb shell ping google.com` 이 `unknown host`). `tools/start-emulator.ps1` 로 켜면 `-dns-server 8.8.8.8,1.1.1.1` 을 붙여 켜고 Virtual Console 포트도 연결한다.
- **`adb` 명령을 못 찾음**: `%LOCALAPPDATA%\Android\Sdk\platform-tools` 를 사용자 PATH 에 추가하고 터미널을 새로 연다.
- **빌드 시 `Invalid file path`**: `local.properties` 의 역슬래시가 하나씩만 들어간 경우. `sdk.dir=C\:\\Users\\<이름>\\AppData\\Local\\Android\\Sdk` 처럼 두 번씩 쓴다.
