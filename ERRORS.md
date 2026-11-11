# 오류와 실패 신호

모듈마다 예외 규칙은 하나다. 인자가 틀리면 `IllegalArgumentException`(받은 값과 허용 범위가 메시지에 있다), 인자는 맞는데 외부 엔진(Play, 저장소, 원격 출처)이 실패하면
모듈 고유 예외 `ReviewPromptException`/`UpdateException`(원인은 `cause`) 또는 그 예외를 담은 결과 값이다. 조용히 기본값으로 바꾸지 않는다.
코루틴 취소(`CancellationException`)는 어디서도 삼키지 않고 그대로 전파한다. 메시지 문구는 바뀔 수 있으니 분기는 타입으로 한다.

## review

### 예외

| 예외 | 언제 | 호출부가 할 일 |
|---|---|---|
| `IllegalArgumentException` | `DefaultReviewPolicy.Config` 임계값이 범위 밖(일수·횟수 음수, 쿨다운 음수, 버전당 한도 1 미만) | 값을 바로잡는다 |
| `IllegalArgumentException` | `ReviewState` 카운터가 음수 | 저장소 구현을 고친다 |
| `IllegalArgumentException` | `SharedPreferencesReviewStore` 파일 이름이 비어 있음 | 이름을 준다 |
| `ReviewPromptException` | 설치된 패키지에 `versionName` 이 없음(기본 버전 공급자). `evaluate` 에서는 던지고, `maybeRequest` 에서는 `Failed` 로 돌아온다 | 매니페스트를 고치거나 `appVersionProvider` 를 넘긴다 |
| `ReviewPromptException` | `SharedPreferences.commit()` 이 실패 | 저장 공간을 확인한다 |
| 저장소 구현의 예외 | `recordLaunch` 등 기록 함수·`reset`·`evaluate` 에서 저장소가 던지면 그대로 전파된다 | 저장소를 고친다 |

### 값으로 알리는 신호

| 신호 | 언제 | 호출부가 할 일 |
|---|---|---|
| `ReviewOutcome.NotEligible(reason)` | 정책이 막음. `reason` 은 첫 번째로 걸린 `ReviewDenyReason` | 정상. 나중에 다시 시도한다 |
| `ReviewOutcome.NotEligible(REQUEST_IN_PROGRESS)` | 같은 `ReviewPrompt` 의 다른 `maybeRequest` 가 Play 흐름을 띄워 둔 상태(기다리지 않는다) | 중복 호출이다. 무시한다 |
| `ReviewOutcome.Requested` | Play 플로우가 오류 없이 끝남 — **다이얼로그가 보였다는 뜻이 아니다** | 표시를 가정하지 않는다 |
| `ReviewOutcome.Failed(cause)` | Play 요청 실패, 저장소 읽기/쓰기 실패, 버전 공급자 실패. `cause` 는 `ReviewPromptException`, `cause.cause` 에 원본 예외 | Play 실패면 카운터가 바뀌지 않아 다음 기회에 재시도된다. 저장 실패는 중복 요청 위험이 있어 기록되지 않았음을 뜻한다 |

## update

### 예외

| 예외 | 언제 | 호출부가 할 일 |
|---|---|---|
| `IllegalArgumentException` | `SemanticVersion.parse` 가 형식이 틀린 문자열(빈 값, `v` 접두, 구간 5개 이상, `Long` 초과, 사전 릴리스 숫자의 선행 0(`1.0.0-01`), ASCII 가 아닌 숫자(`１.0.0`·`1.2.٣`) 등)을 받음 | `parseOrNull` 을 쓰거나 입력을 고친다 |
| `IllegalArgumentException` | `VersionRequirement` 의 이름 형식 오류(메시지에 필드 이름과 받은 값)·코드 음수. 빈 값·공백은 오류가 아니라 설정 없음 | 값을 바로잡는다 |
| `IllegalArgumentException` | `InstalledVersion` 의 빈 이름·코드 음수, 전략 `Config` 의 범위 오류 | 값을 바로잡는다 |
| `IllegalArgumentException` | `VersionNameParser` 가 설치 버전 이름을 해석하지 못함 — `ForceUpdateChecker.create`·`AppUpdater.create`(출처가 있을 때) 생성 시점, `VersionRequirement.evaluate` | `versionNameParser` 에 맞는 규칙을 넘긴다 |
| `IllegalArgumentException` | `AppUpdater.create` 의 `playTimeoutMillis` 가 0 이하 | 양수를 준다 |
| `IllegalArgumentException` | `AppUpdater.start` 에 Play 로 처리하지 않는 동작(`None`, `BlockUntilStoreUpdate`, `BlockUntilCheckSucceeds`)을 넘김 | `check` 결과를 `when` 으로 나눠 부른다 |
| `UpdateException` | `InstalledVersion.of` 가 패키지 정보를 못 읽거나 `versionName` 이 없음 | `InstalledVersion(...)` 을 직접 만들어 넘긴다 |
| `UpdateException` | `PlayStoreLink.open` 이 Play 스토어 앱도 웹 브라우저도 찾지 못함(원인은 `ActivityNotFoundException`) | 다른 스토어 링크로 대체하거나 안내한다 |

### 값으로 알리는 신호

| 신호 | 언제 | 호출부가 할 일 |
|---|---|---|
| `ForceUpdateDecision.CheckFailed(cause, SOURCE_UNAVAILABLE)` | 출처가 `IllegalArgumentException` 외의 예외를 던짐(네트워크·서버) | 일시적이다. 기본 결정표는 통과(fail-open) |
| `ForceUpdateDecision.CheckFailed(cause, INVALID_REQUIREMENT)` | 출처가 `IllegalArgumentException` 을 던짐 — 대개 원격 값 형식 오류(`VersionRequirement` 생성 실패) | 설정 오류다. 오류 보고로 알리고 원격 값을 고친다. 막으려면 `CheckFailurePolicy.FAIL_CLOSED_ON_INVALID_VALUE` |
| `ForceUpdateDecision.CheckFailed(cause, INVALID_INSTALLED_VERSION)` | 생성 시 검사를 통과한 `VersionNameParser` 가 판정 때 실패(비결정적 규칙) | 규칙을 순수 함수로 고친다 |
| `UpdateAction.BlockUntilCheckSucceeds(failure)` | `CheckFailurePolicy` 가 실패를 막기로 함(기본 정책에서는 안 나옴) | 다시 확인 버튼을 준다 |
| `PlayUpdateAvailability.Failed(cause)` | Play 스토어 없음·서비스 오류·사이드로드 빌드, 조회가 `playTimeoutMillis` 를 넘김 | 정상 상황일 수 있다. 강제 판정이 있으면 결정표가 `BlockUntilStoreUpdate` 로 돌린다 |
| `PlayUpdateAvailability.Unknown` | Play 가 상태 코드를 `UNKNOWN` 으로 줌 | 재시도하거나 무시한다 |
| `PlayUpdateResult.Failed(cause)` | 허용되지 않는 방식, 흐름 오류 코드, Task 실패, 설치 요청 실패, Play 가 런처 흐름을 띄우지 않음, 런처 경로 미지원 구현 | 다른 방식 또는 스토어 링크(`PlayStoreLink.open`)로 대체한다. 강제면 차단은 유지한다 |
| `PlayUpdateResult.Canceled` | 사용자가 Play 흐름을 취소(뒤로 가기) | 강제면 차단 화면을 유지하고 다시 안내한다 |
| `PlayUpdateResult.Launched` | 런처 경로로 흐름을 띄움. 결과는 런처 콜백(`PlayUpdateResult.fromActivityResult`) | `refresh` 로 다시 판정한다 |
| `PlayInstallStatus.REQUIRES_UI_INTENT` | Play 가 사용자 확인 화면을 다시 띄워야 진행 | `refresh` 후 흐름을 다시 시작한다 |

## update-compose

예외를 던지지 않는다. 화면 모델은 `UpdateUiModel.from` 이 모든 판정·동작 조합에 대해 정의되어 있다. 강제 판정이면 동작과 무관하게 `Blocking`,
실패 정책이 막으면 `CheckFailed` 다.
