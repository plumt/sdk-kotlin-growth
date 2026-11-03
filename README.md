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

when (val outcome = review.maybeRequest(activity)) {      // 요청해도 좋은 자연스러운 지점에서
    is ReviewOutcome.NotEligible -> log(outcome.reason)   // 정책이 막은 이유
    ReviewOutcome.Requested -> Unit                        // 플로우가 끝났다 — 다이얼로그가 보였다는 뜻은 아니다
    is ReviewOutcome.Failed -> log(outcome.cause)          // Play 실패. 카운터는 바뀌지 않았다
}
```

### 표시 여부를 속이지 않는다

Play In-App Review 는 쿼터와 내부 판단으로 다이얼로그를 조용히 건너뛸 수 있고, 앱은 그것을 알 수 없다(건너뛰어도 플로우는 똑같이 "끝남" 이다).
그래서 이 라이브러리의 성공은 `Requested`(플로우가 끝났다) 일 뿐 "표시됨" 이 아니다. 정책의 쿨다운은 "요청했다" 를 기준으로 센다.

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

스레드: 모든 함수는 `suspend` 이고 한 `ReviewPrompt` 안에서 직렬화된다(Mutex). 같은 저장소를 여러 `ReviewPrompt` 가 공유하면 직렬화가 보장되지 않는다.
테스트: Play 의 `FakeReviewManager` 를 `PlayReviewLauncher.create(manager)` 에 넘긴다.

## update

```kotlin
dependencies { implementation("net.plumt.growth:update:0.0.1") }
```

### 1. 최소 버전 강제 업데이트

원격 출처는 앱이 `MinimumVersionSource` 로 꽂는다(Firebase Remote Config, 자체 서버, 정적 JSON 등 — 라이브러리는 어느 SDK 에도 의존하지 않는다).

```kotlin
val source = MinimumVersionSource {
    VersionRequirement(
        minimumVersionName = remoteConfig.getString("min_version"),   // 이보다 낮으면 강제
        recommendedVersionName = remoteConfig.getString("latest"),    // 이보다 낮으면 권장
    )
}
val checker = ForceUpdateChecker.create(source, InstalledVersion.of(context))
when (val d = checker.check()) {
    is ForceUpdateDecision.Required -> blockApp()
    is ForceUpdateDecision.Recommended -> suggest()
    ForceUpdateDecision.UpToDate -> Unit
    is ForceUpdateDecision.CheckFailed -> failOpenOrClosed(d.cause)   // 정책은 앱이 정한다
}
```

버전 비교는 `SemanticVersion`(SemVer 2.0 우선순위: 숫자 구간 비교, `1.2` == `1.2.0`, 사전 릴리스는 정식보다 낮음, 빌드 메타데이터 무시)과 `versionCode` 를 함께 쓴다.
이름·코드를 둘 다 주면 하나라도 모자라면 해당한다. 해석할 수 없는 버전 문자열은 0 으로 바꾸지 않고 `IllegalArgumentException`(→ `CheckFailed`)이다.

### 2. Play In-App Updates + 결정표

```kotlin
val updater = AppUpdater.create(context, minimumVersionSource = source)
val result = updater.check()                       // 원격 판정 + Play 상태 → UpdateAction
when (val action = result.action) {
    is UpdateAction.BlockUntilStoreUpdate -> showBlocking(PlayStoreLink.intent(context))
    UpdateAction.None -> Unit
    else -> updater.start(activity, action)        // StartPlay / ResumePlayImmediate / InstallDownloaded
}
updater.installStates().collect { /* 유연 업데이트 진행률 */ }
```

`UpdateActionResolver` 의 결정표(위에서부터 먼저 맞는 줄):

| 순서 | 조건 | 동작 |
|---|---|---|
| 1 | Play 즉시 업데이트 진행 중 | `ResumePlayImmediate` |
| 2 | 강제 + Play 에 새 버전 + 즉시 허용 | `StartPlay(IMMEDIATE)` |
| 3 | Play 가 내려받은 업데이트 보유 | `InstallDownloaded` |
| 4 | 강제 (그 밖의 모든 Play 상태) | `BlockUntilStoreUpdate` |
| 5 | Play 내려받는 중 | `None` |
| 6 | Play 에 새 버전 | `PlayUpdateStrategy` 선택, 없으면 권장 + 유연 허용 시 `StartPlay(FLEXIBLE)` |
| 7 | 그 밖 | `None` |

원격 출처 조회 실패(`CheckFailed`)는 강제도 권장도 아닌 것으로 본다(fail-open). 실패 때 막고 싶으면 `result.force` 를 직접 본다.
기본 전략 `DefaultPlayUpdateStrategy` 는 Play 우선순위 4 이상이면 즉시, 아니면 유연이다.

### 확장 지점

- `MinimumVersionSource` — 원격 최소·권장 버전 출처
- `PlayUpdateStrategy` — 강제가 아닐 때 즉시/유연 선택
- `PlayUpdateClient` — Play 래퍼(다른 배포 채널은 직접 구현). 테스트는 `FakeAppUpdateManager` 를 `PlayUpdateClient.create(manager)` 에 넘긴다
- `UpdateActionResolver` — 결정표는 순수 함수라 따로 불러 쓸 수 있다

Play 인앱 업데이트는 Play 스토어로 설치된 빌드에서만 동작한다. 사이드로드·에뮬레이터에서는 `PlayUpdateAvailability.Failed` 가 되고,
강제 판정이 있으면 `BlockUntilStoreUpdate` 가 된다.

## update-compose

```kotlin
dependencies { implementation("net.plumt.growth:update-compose:0.0.1") }
```

```kotlin
val model = UpdateUiModel.from(result)             // 순수 함수 — 무엇을 보여줄지
UpdateGate(model, onConfirm = { /* 시작/스토어/설치 */ }, onDismiss = { /* 나중에 */ }) {
    AppContent()                                    // Blocking 이면 그려지지 않는다
}
```

- 차단 화면에는 닫는 동작이 없다(업데이트 외의 탈출구를 만들지 않는다)
- 문구는 `UpdateTexts`(기본: 영어·한국어 리소스), 화면은 `dialog`·`blocking` 슬롯으로 통째로 교체한다. `UpdateGate` 없이 `UpdateUiModel` 만 써도 된다

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
