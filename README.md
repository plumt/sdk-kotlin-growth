# sdk-kotlin-growth

앱 성장 도구 모음 — 인앱 리뷰 요청 시점 판정과 앱 업데이트(강제·권장) 확인을 Android 라이브러리로 제공한다.
DI 프레임워크(Hilt 등)와 Firebase 에 의존하지 않는다. 생성은 팩토리 함수이고 동작을 바꿀 지점은 모두 인터페이스다.

| 모듈 | 좌표 | 내용 |
|---|---|---|
| `review` | `net.plumt.growth:review` | 리뷰를 **언제** 물을지 정책으로 판정하고 Play In-App Review 로 요청 |
| `update` | `net.plumt.growth:update` | 최소 버전 강제 업데이트 판정 + Play In-App Updates(유연·즉시) + 둘을 합친 결정표 |
| `update-compose` | `net.plumt.growth:update-compose` | 안내 다이얼로그·차단 화면(Compose, 슬롯으로 교체 가능) |

최소 요구사항: minSdk 23, compileSdk 36, Java 11 바이트코드, Kotlin 2.2. `update-compose` 는 Compose BOM 2025.02.00 이상.
minSdk 는 Play In-App Review/Updates 라이브러리의 최소 지원(21)보다 높게 잡은 보수적 값이다.

## review

```kotlin
dependencies { implementation("net.plumt.growth:review:0.0.1") }
```

```kotlin
val review = ReviewPrompt.create(context)       // 앱에 하나만 두고 공유한다

review.recordLaunch()                            // 앱을 켤 때마다
review.recordSignificantEvent()                  // 사용자가 핵심 가치를 얻은 순간마다
review.recordNegativeMoment()                    // 크래시·오류·결제 실패 등 불쾌했을 순간
review.recordDeclined()                          // 앱의 자체 "마음에 드세요?" 질문을 사용자가 거절했을 때

lifecycleScope.launch {                                       // 화면 범위에서 부른다(Activity 참조를 오래 잡지 않게)
    when (val outcome = review.maybeRequest(activity)) {      // 요청해도 좋은 자연스러운 지점에서
        is ReviewOutcome.NotEligible -> log(outcome.reason)   // 정책이 막은 이유(또는 REQUEST_IN_PROGRESS)
        ReviewOutcome.Requested -> Unit                        // 플로우가 끝났다 — 다이얼로그가 보였다는 뜻은 아니다
        is ReviewOutcome.Failed -> log(outcome.cause)          // Play·저장소 실패. 카운터는 바뀌지 않았다
    }
}
```

### 표시 여부를 속이지 않는다

Play In-App Review 는 쿼터와 내부 판단으로 다이얼로그를 조용히 건너뛸 수 있고, 앱은 그것을 알 수 없다(건너뛰어도 플로우는 똑같이 "끝남" 이다).
그래서 이 라이브러리의 성공은 `Requested`(플로우가 끝났다) 일 뿐 "표시됨" 이 아니다. 정책의 쿨다운은 "요청했다" 를 기준으로 센다.

Play 의 실제 제약(라이브러리가 바꿀 수 없다):

- 카드는 Play 스토어로 설치된 빌드에서만 뜬다. 디버그·사이드로드 설치본은 카드 없이 "끝남" 이 되고, 이 라이브러리는 그것도 "요청함" 으로 기록한다 —
  그 기기의 그 버전 기회(`maxRequestsPerVersion`)가 소진된다
- 시험은 내부 앱 공유·내부 테스트 트랙 설치본으로 하거나, 단위 테스트에서 Play 의 `FakeReviewManager` 를 쓴다
- 쿼터(같은 사용자에게 짧은 기간 여러 번 묻지 않음), 이미 리뷰한 계정, 기기 상태에 따라 카드가 뜨지 않아도 결과는 같다
- 기록을 지우려면 `review.reset()` — 라이브러리의 카운터만 지우며 Play 쿼터는 지우지 못한다

### 기본 정책 `DefaultReviewPolicy`

모든 조건을 만족해야 허락한다. 막는 이유는 `ReviewDenyReason` 으로 돌려준다(검사 순서).

| 조건 | 기본값 |
|---|---|
| 첫 실행 후 경과 일수 | 3 |
| 실행 횟수 | 5 |
| 의미 있는 사건 수 | 3 |
| 부정적 순간 후 쿨다운 | 14일 |
| 사용자 거절 후 쿨다운 | 120일 |
| 마지막 요청 후 쿨다운 | 60일 |
| 앱 버전당 최대 요청 수 | 1 |

```kotlin
val policy = DefaultReviewPolicy(DefaultReviewPolicy.Config(minLaunchCount = 10, cooldownAfterRequest = 90.days))
val review = ReviewPrompt.create(context, policy = policy)
```

### 확장 지점

- `ReviewPolicy` — 판정 규칙을 통째로 교체(순수 함수 `evaluate(state, nowMillis, appVersion)`)
- `ReviewStore` — 카운터 저장소(기본 `SharedPreferencesReviewStore`, 테스트용 `InMemoryReviewStore`). DataStore·서버로 교체 가능
- `ReviewLauncher` — Play 대신 다른 리뷰 수단(자체 다이얼로그 등)
- `ReviewClock` — 시각 출처. 테스트에서 시간을 고정한다

스레드: 모든 함수는 `suspend` 이다. 저장소 읽기·쓰기는 한 `ReviewPrompt` 안에서 직렬화되고(Mutex), Play 흐름은 잠금 밖에서 기다리므로
리뷰 카드가 떠 있는 동안에도 `recordLaunch` 등은 멈추지 않는다. 흐름이 떠 있는 동안의 두 번째 `maybeRequest` 는 기다리지 않고
`NotEligible(REQUEST_IN_PROGRESS)` 다. 같은 저장소를 여러 `ReviewPrompt` 가 공유하면 직렬화가 보장되지 않는다.

테스트: Play 의 `FakeReviewManager` 를 `PlayReviewLauncher.create(manager)` 에 넘긴다. 이 진입점은 Play 타입을 받지만 라이브러리는 Play 를
`implementation` 으로만 의존하므로, 소비자 테스트에 `testImplementation("com.google.android.play:review:2.0.2")` 를 직접 추가한다.
예외 이름은 Play 의 `ReviewException` 과 겹치지 않게 `ReviewPromptException` 이다.

## update

```kotlin
dependencies { implementation("net.plumt.growth:update:0.0.1") }
```

### 1. 최소 버전 강제 업데이트

원격 출처는 앱이 `MinimumVersionSource` 로 꽂는다(Firebase Remote Config, 자체 서버, 정적 JSON 등 — 라이브러리는 어느 SDK 에도 의존하지 않는다).

```kotlin
val source = MinimumVersionSource {
    remoteConfig.fetchAndActivate().await()                  // 실패하면 던진다 → CheckFailed(SOURCE_UNAVAILABLE)
    VersionRequirement(
        minimumVersionCode = remoteConfig.getLong("min_version_code").takeIf { it > 0 },   // 가장 견고한 기준
        minimumVersionName = remoteConfig.getString("min_version"),                         // "" 는 설정 없음
        recommendedVersionName = remoteConfig.getString("latest_version"),                 // 키가 없어도 최소 버전은 살아 있다
    )
}
val checker = ForceUpdateChecker.create(source, InstalledVersion.of(context))
when (val d = checker.check()) {
    is ForceUpdateDecision.Required -> blockApp()
    is ForceUpdateDecision.Recommended -> suggest()
    ForceUpdateDecision.UpToDate -> Unit
    is ForceUpdateDecision.CheckFailed -> when (d.reason) {
        CheckFailureReason.SOURCE_UNAVAILABLE -> Unit                 // 오프라인 등 — 기본은 통과(fail-open)
        else -> reportConfigError(d.cause)                            // 원격 값 형식 오류 — 설정을 고친다
    }
}
```

원격 값 규칙(`VersionRequirement`):

| 값 | 의미 |
|---|---|
| `null`, `""`, 공백 | **설정 없음** — 그 필드만 빠지고 다른 필드의 요구는 그대로다. Remote Config 의 없는 키(`""`)가 강제 업데이트를 끄지 않는다 |
| `" 2.0.0 "` | 앞뒤 공백을 떼고 `2.0.0` |
| `"v2.0.0"`, `"2.0.0.0.1"` 등 형식 오류 | 생성 시 `IllegalArgumentException` → `CheckFailed(INVALID_REQUIREMENT)`. "요구 없음" 으로 바꾸지 않는다 |
| 코드 음수 | `IllegalArgumentException` |

버전 비교는 `SemanticVersion`(SemVer 2.0 우선순위: 숫자 구간 비교, `1.2` == `1.2.0`, 사전 릴리스는 정식보다 낮음, 빌드 메타데이터 무시)과 `versionCode` 를 함께 쓴다.
이름·코드를 둘 다 주면 하나라도 모자라면 해당한다. 빌드마다 반드시 올라가는 `versionCode` 가 가장 견고하다.

**설치 버전 이름**은 `VersionNameParser` 로 해석한다. 기본 `NUMERIC_PREFIX` 는 앞쪽 숫자 구간만 읽고 접미사를 버린다
(`1.4.0-prod`·`1.4.0-debug`·`1.4.0 (45)` → `1.4.0`). 접미사를 SemVer 사전 릴리스로 읽으면 `1.4.0-prod < 1.4.0` 이 되어 최신 정식 빌드가 막히기 때문이다.
숫자로 시작하지 않거나 구간이 5개 이상인 이름(`v1.2`, `2026.11.03.1.2`)은 `ForceUpdateChecker.create`·`AppUpdater.create` 가 **생성 시**
`IllegalArgumentException` 으로 알린다 — 강제 업데이트가 조용히 꺼지는 대신 개발 중에 드러난다. 다른 형식이면 규칙을 구현해 넘긴다
(`versionNameParser = VersionNameParser { ... }`). 사전 릴리스까지 구분하려면 `VersionNameParser.STRICT`.

### 2. Play In-App Updates + 결정표

```kotlin
val updater = AppUpdater.create(context, minimumVersionSource = source)

// Activity: Play 권장 방식인 Activity Result 런처로 흐름을 띄운다(재생성돼도 결과가 새 Activity 로 온다)
private val updateLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
    val outcome = PlayUpdateResult.fromActivityResult(r.resultCode)   // Canceled 여도 강제면 차단 화면은 그대로다
    lifecycleScope.launch { render(updater.refresh()) }
}

lifecycleScope.launch { render(updater.check()) }                  // 시작 시 한 번
override fun onResume() {                                           // 즉시 업데이트 재개·백그라운드 다운로드 완료를 잡는다
    super.onResume()
    lifecycleScope.launch { render(updater.refresh()) }             // 원격 출처는 다시 부르지 않는다
}

suspend fun render(result: UpdateCheckResult) {
    showBlockingScreen(result.isBlocking)                           // 강제면 Play 동작과 무관하게 본문을 막는다
    if (result.action == UpdateAction.ResumePlayImmediate) {        // Play 문서대로 진행 중이던 즉시 업데이트는 바로 이어 간다
        updater.start(updateLauncher, result.action)
    }
    // 그 밖의 동작은 차단 화면·다이얼로그의 버튼이 실행한다(취소 뒤 onResume 마다 흐름을 다시 띄우는 반복을 피한다)
}
// 버튼: Play 동작 → updater.start(updateLauncher, action), BlockUntilStoreUpdate → PlayStoreLink.open(context),
//       BlockUntilCheckSucceeds → updater.check()
```

`UpdateActionResolver` 의 결정표(위에서부터 먼저 맞는 줄):

| 순서 | 조건 | 동작 |
|---|---|---|
| 0 | 원격 판정 실패 + `CheckFailurePolicy` 가 막음 | `BlockUntilCheckSucceeds` |
| 1 | Play 즉시 업데이트 진행 중 | `ResumePlayImmediate` |
| 2 | 강제 + Play 에 새 버전 + 즉시 허용 | `StartPlay(IMMEDIATE)` |
| 3 | Play 가 내려받은 업데이트 보유 | `InstallDownloaded` |
| 4 | 강제 (그 밖의 모든 Play 상태) | `BlockUntilStoreUpdate` |
| 5 | Play 내려받는 중 | `None` |
| 6 | Play 에 새 버전 | `PlayUpdateStrategy` 선택, 없으면 권장 + 유연 허용 시 `StartPlay(FLEXIBLE)` |
| 7 | 그 밖 | `None` |

**강제면 화면은 항상 차단이다.** 1~3번 줄처럼 강제 상황에서 Play 동작이 나와도 앱 본문을 열지 않는다 — Play 즉시 업데이트 화면은 뒤로 가기로
취소되고, 내려받은 업데이트는 미룰 수 있고, 흐름 중 Activity 가 재생성되면 결과가 사라진다. `UpdateCheckResult.isBlocking` 이 `true` 인 동안
차단 화면을 유지하고 그 위에서 Play 동작을 실행한다. Play 흐름이 취소·실패해도 차단은 풀리지 않는다(update-compose 가 그렇게 한다).

**최소 버전은 스토어 반영을 확인한 뒤에 올린다.** Play 에 출시해도 사용자 기기에 새 버전이 보이기까지 시간이 걸린다.
그 전에 원격 최소 버전을 올리면 Play 에 새 버전이 없어 4번 줄 `BlockUntilStoreUpdate` 가 되고, 사용자는 스토어에서도 업데이트할 수 없는 채로
차단 화면에 갇힌다. 순서는 ① 새 버전 출시 → ② 실제 기기의 Play 에서 새 버전이 보이는지 확인 → ③ 최소 버전 올리기다.
단계적 출시(staged rollout) 중이면 아직 받지 못한 사용자가 갇히므로 100% 출시 뒤에 올린다. 서두를 필요가 없으면 권장 버전부터 올린다.

원격 판정 실패(`CheckFailed`)의 기본은 fail-open(오프라인·일시 장애로 앱을 막지 않음)이다. 실패는 `result.force` 에 남는다.
막으려면 `AppUpdater.create(onCheckFailure = ...)`:

| 정책 | 동작 |
|---|---|
| `CheckFailurePolicy.FAIL_OPEN` (기본) | 어떤 실패에도 막지 않는다 |
| `CheckFailurePolicy.FAIL_CLOSED_ON_INVALID_VALUE` | 설정 오류(`INVALID_REQUIREMENT`·`INVALID_INSTALLED_VERSION`)만 막고 네트워크 실패는 통과 |
| `CheckFailurePolicy.FAIL_CLOSED` | 모든 실패에 막는다. 오프라인 사용자도 막히므로 재시도 버튼이 필수다 |

Play 상태 조회에는 시간 제한(기본 5초, `playTimeoutMillis`)이 있다. 넘으면 `PlayUpdateAvailability.Failed` 로 접어 시작 화면이 멈추지 않는다.
기본 전략 `DefaultPlayUpdateStrategy` 는 Play 우선순위 4 이상이면 즉시, 아니면 유연이다.

### 스토어 링크와 배포 채널

- `PlayStoreLink.open(context)` 는 Play 스토어 앱(`com.android.vending` 지정 — 다른 스토어가 `market://` 를 가로채지 않게) → 웹 순으로 열고, 둘 다 없으면
  크래시 대신 `UpdateException` 이다
- Play 스토어가 없는 기기·다른 스토어 배포본은 웹 Play 로도 설치할 수 없다. 그 채널은 차단 화면의 버튼이 그 스토어를 열도록 앱이 구현하고,
  Play 상태는 `PlayUpdateClient` 를 직접 구현해 바꾼다
- **최소 버전은 새 버전이 100% 출시된 뒤에 올린다.** 단계적 출시 중에 올리면 아직 배포받지 못한 사용자는 Play 에 새 버전이 없어
  (`NotAvailable`) 스토어 페이지에서도 업데이트할 수 없는 차단 화면에 갇힌다

### 확장 지점

- `MinimumVersionSource` — 원격 최소·권장 버전 출처
- `VersionNameParser` — 설치 버전 이름 해석 규칙
- `CheckFailurePolicy` — 원격 판정 실패 때 막을지
- `PlayUpdateStrategy` — 강제가 아닐 때 즉시/유연 선택
- `PlayUpdateClient` — Play 래퍼(다른 배포 채널은 직접 구현). 테스트는 `FakeAppUpdateManager` 를 `PlayUpdateClient.create(manager)` 에 넘긴다 —
  이 진입점은 Play 타입을 받으므로 소비자 테스트에 `testImplementation("com.google.android.play:app-update:2.1.0")` 를 직접 추가한다
- `UpdateActionResolver` — 결정표는 순수 함수라 따로 불러 쓸 수 있다

Play 인앱 업데이트는 Play 스토어로 설치된 빌드에서만 동작한다. 사이드로드·에뮬레이터에서는 `PlayUpdateAvailability.Failed` 가 되고,
강제 판정이 있으면 `BlockUntilStoreUpdate` 가 된다.

의존: `androidx.activity` 를 `api` 로 노출한다. 런처를 받는 `start(ActivityResultLauncher<IntentSenderRequest>, …)` 가 공개 API 이기 때문이다
(Play 가 권장하는 Activity Result 경로. 대부분의 앱은 이미 이 라이브러리를 쓴다). Play 라이브러리 타입은 테스트용 진입점 외에는 드러내지 않는다.

## update-compose

```kotlin
dependencies { implementation("net.plumt.growth:update-compose:0.0.1") }
```

```kotlin
val model = UpdateUiModel.from(result)             // 순수 함수 — 무엇을 보여줄지(강제면 항상 Blocking)
UpdateGate(
    model,
    onConfirm = {
        when (model) {
            is UpdateUiModel.Blocking ->
                if (model.opensStore) PlayStoreLink.open(context)
                else scope.launch { updater.start(updateLauncher, model.action) }   // 즉시 업데이트·재개·설치
            is UpdateUiModel.CheckFailed -> scope.launch { result = updater.check() }   // 다시 확인
            is UpdateUiModel.Optional -> scope.launch { updater.start(updateLauncher, model.action) }
            UpdateUiModel.ReadyToInstall -> scope.launch { updater.start(updateLauncher, UpdateAction.InstallDownloaded) }
            UpdateUiModel.Hidden -> Unit
        }
    },
    onDismiss = { /* 나중에 */ },
) {
    AppContent()                                    // Blocking·CheckFailed 이면 그려지지 않는다
}
```

| 모델 | 언제 | 본문 |
|---|---|---|
| `Blocking(required, action)` | 강제 판정 — Play 동작이 무엇이든 | 그리지 않는다. 버튼은 `action` 실행 |
| `CheckFailed(failure)` | 실패 정책이 막음 | 그리지 않는다. 버튼은 다시 확인 |
| `ReadyToInstall` | 강제 아님 + 내려받음 | 위에 다이얼로그 |
| `Optional(action)` | 강제 아님 + 유연 업데이트 권함 | 위에 다이얼로그 |
| `Hidden` | 그 밖(강제 아닌 즉시 업데이트 포함) | 그대로 |

- 차단 화면에는 닫는 동작이 없다(업데이트·다시 확인 외의 탈출구를 만들지 않는다). Play 즉시 업데이트 화면을 뒤로 가기로 닫아도 그 아래는 차단 화면이다
- 문구는 `UpdateTexts`(기본: 영어·한국어 리소스), 화면은 `dialog`·`blocking` 슬롯으로 통째로 교체한다. `UpdateGate` 없이 `UpdateUiModel` 만 써도 된다
- `Modifier`·`@Composable` 을 공개 API 로 쓰므로 Compose UI 를 `api` 로 노출한다

## 위협 모델

- 강제 업데이트는 **편의 장치**이지 보안 경계가 아니다. 원격 값은 앱 안에서 검사되므로 변조된 클라이언트는 우회할 수 있다. 서버 쪽에서 오래된 클라이언트를 거부해야 하는 기능은 서버가 버전을 검사한다
- 출처(`MinimumVersionSource`)가 평문 HTTP 거나 서명 없는 응답이면 중간자가 낮은 최소 버전을 줄 수 있다. 앱이 TLS 로 구현한다
- 라이브러리는 로그를 남기지 않고 개인정보를 수집하지 않는다. 리뷰 카운터는 횟수·시각·앱 버전 문자열만 `SharedPreferences` 에 저장한다

## 오류와 변경 이력

오류 규칙은 [ERRORS.md](ERRORS.md), 변경 이력은 [CHANGELOG.md](CHANGELOG.md).

## 버전

모듈마다 [SemVer](https://semver.org/lang/ko/) 를 따로 쓰고 태그는 `<모듈>-v<버전>`(예: `review-v0.0.1`)이다.
1.0.0 전까지는 마이너 버전에서도 공개 API 가 바뀔 수 있다. 공개 API 는 `<모듈>/api/*.api` 로 고정하고 CI 가 대조한다.
모든 모듈은 Kover 라인 커버리지 90% 이상을 빌드 조건으로 건다(`./gradlew build`).

## 라이선스

[Apache License 2.0](LICENSE)
