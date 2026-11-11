# Changelog

모듈별로 적는다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/), 버전은 [SemVer](https://semver.org/lang/ko/) 이다.

## review

### [0.0.1] - 2026-11-11

#### 추가
- `ReviewPrompt` 진입점: 실행·의미 있는 사건·부정적 순간·거절 기록, 판정(`evaluate`), 요청(`maybeRequest`)
- `ReviewPolicy` 와 기본 구현 `DefaultReviewPolicy`(설치 후 일수·실행 수·사건 수·쿨다운 3종·버전당 한도), 거부 이유 `ReviewDenyReason`
- `ReviewStore` 포트와 `SharedPreferencesReviewStore`·`InMemoryReviewStore`
- `ReviewLauncher` 포트와 `PlayReviewLauncher`(Play In-App Review). 표시 여부를 알리지 않는다
- 시각을 주입하는 `ReviewClock`
- `ReviewPrompt.reset()` — 카운터 초기화(디버그 시험·사용자 데이터 초기화)
- 실패 예외 `ReviewPromptException`(Play 의 `ReviewException` 과 이름이 겹치지 않게)

#### 출시 전 검토 반영
- `maybeRequest` 가 Play 흐름 동안 잠금을 잡아 기록 함수가 멈추던 문제 — 잠금은 저장소 구간만 감싸고, 흐름 중 중복 요청은 `REQUEST_IN_PROGRESS` 로 바로 돌려준다. 흐름 뒤 기록은 최신 상태를 다시 읽어 흐름 중 기록을 덮지 않는다
- 버전 공급자 실패가 `maybeRequest` 에서 예외로 새던 것을 `ReviewOutcome.Failed` 로 통일

## update

### [0.0.1] - 2026-11-11

#### 추가
- `SemanticVersion`(SemVer 2.0 우선순위), `InstalledVersion`, `VersionRequirement`
- 설치 버전 이름 해석 규칙 `VersionNameParser`(기본 `NUMERIC_PREFIX` — 빌드 접미사 무시, `STRICT`)
- `MinimumVersionSource` 포트와 `ForceUpdateChecker`(`UpToDate`·`Recommended`·`Required`·`CheckFailed` + `CheckFailureReason`)
- 원격 판정 실패 정책 `CheckFailurePolicy`(`FAIL_OPEN` 기본·`FAIL_CLOSED_ON_INVALID_VALUE`·`FAIL_CLOSED`)와 `UpdateAction.BlockUntilCheckSucceeds`
- `PlayUpdateClient`(가용성·유연/즉시 시작·Activity Result 런처 시작·설치 상태 Flow·설치 완료)와 `PlayUpdateStrategy`/`DefaultPlayUpdateStrategy`
- 강제 판정 x Play 상태 결정표 `UpdateActionResolver` 와 파사드 `AppUpdater`(`check`·`refresh`·Play 조회 시간 제한)
- `PlayStoreLink`(`open` — Play 스토어 앱 → 웹 대체)

#### 출시 전 검토 반영
- Remote Config 의 없는 키(`""`)가 `VersionRequirement` 생성을 실패시켜 최소 버전 강제까지 꺼지던 문제 — 빈 값·공백은 설정 없음, 형식 오류는 `CheckFailed(INVALID_REQUIREMENT)` 로 네트워크 실패와 구분
- 설치 버전 이름의 빌드 접미사(`-prod`·` (45)` 등)가 사전 릴리스로 읽혀 최신 정식 빌드가 막히거나, 해석 실패로 강제 판정이 조용히 꺼지던 문제 — 기본 규칙은 숫자 부분만 비교하고, 해석 실패는 생성 시 `IllegalArgumentException`
- 진행 중인 즉시 업데이트 재개 때 허용 검사로 재개가 실패할 수 있던 문제 — 재개는 검사하지 않는다
- Play 조회 무기한 대기 — 기본 5초 시간 제한
- `onResume` 재확인 수단이 없던 문제 — `AppUpdater.refresh()`
- `market://` 를 다른 스토어가 가로채거나 스토어가 없으면 크래시하던 문제 — Play 스토어 앱 지정, `PlayStoreLink.open` 대체 경로
- `InstallStatus.REQUIRES_UI_INTENT` 를 `UNKNOWN` 으로 접던 것 — `PlayInstallStatus.REQUIRES_UI_INTENT`
- 사전 릴리스 숫자 식별자의 선행 0 을 받아 `equals`·`hashCode` 가 어긋나던 것 — 거부

#### Android 정규식 차이 반영
- `SemanticVersion`·`VersionNameParser.NUMERIC_PREFIX` 의 숫자를 `\d` 대신 `[0-9]` 로 적었다. Android 정규식은 `\d` 가
  유니코드 숫자('٣'·'３')까지 맞춰 기기와 JVM 의 해석이 갈렸다. 이제 어디서나 ASCII 숫자만 숫자다(유니코드 숫자는 형식 오류, 접두 규칙에서는 접미사)

## update-compose

### [0.0.1] - 2026-11-11

#### 추가
- `UpdateUiModel`(순수 판정), `UpdateGate`, `DefaultUpdateDialog`, `DefaultBlockingScreen`, `UpdateTexts`(영어·한국어 리소스)
- 실패 정책이 막을 때의 `UpdateUiModel.CheckFailed`(다시 시도 화면)

#### 출시 전 검토 반영
- 강제 판정인데 Play 즉시 업데이트·재개·내려받음이면 본문을 열어(`Hidden`·닫을 수 있는 `ReadyToInstall`) 뒤로 가기·"나중에" 로 우회되던 문제 — 강제면 Play 동작과 무관하게 `Blocking(required, action)` 이고 버튼이 그 동작을 실행한다. 강제 여부를 잃는 `from(action)` 은 없앴다
- 공개 API 가 드러내는 Compose UI 를 `api` 로 노출
