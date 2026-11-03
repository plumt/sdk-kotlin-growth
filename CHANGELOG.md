# Changelog

모듈별로 적는다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/), 버전은 [SemVer](https://semver.org/lang/ko/) 이다.

## review

### [0.0.1] - 미발행

#### 추가
- `ReviewPrompt` 진입점: 실행·의미 있는 사건·부정적 순간·거절 기록, 판정(`evaluate`), 요청(`maybeRequest`)
- `ReviewPolicy` 와 기본 구현 `DefaultReviewPolicy`(설치 후 일수·실행 수·사건 수·쿨다운 3종·버전당 한도), 거부 이유 `ReviewDenyReason`
- `ReviewStore` 포트와 `SharedPreferencesReviewStore`·`InMemoryReviewStore`
- `ReviewLauncher` 포트와 `PlayReviewLauncher`(Play In-App Review). 표시 여부를 알리지 않는다
- 시각을 주입하는 `ReviewClock`

## update

### [0.0.1] - 미발행

#### 추가
- `SemanticVersion`(SemVer 2.0 우선순위), `InstalledVersion`, `VersionRequirement`
- `MinimumVersionSource` 포트와 `ForceUpdateChecker`(`UpToDate`·`Recommended`·`Required`·`CheckFailed`)
- `PlayUpdateClient`(가용성·유연/즉시 시작·설치 상태 Flow·설치 완료)와 `PlayUpdateStrategy`/`DefaultPlayUpdateStrategy`
- 강제 판정 x Play 상태 결정표 `UpdateActionResolver` 와 파사드 `AppUpdater`
- `PlayStoreLink`

## update-compose

### [0.0.1] - 미발행

#### 추가
- `UpdateUiModel`(순수 판정), `UpdateGate`, `DefaultUpdateDialog`, `DefaultBlockingScreen`, `UpdateTexts`(영어·한국어 리소스)
