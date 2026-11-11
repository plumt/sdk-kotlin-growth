package net.plumt.growth.update

import android.app.Activity
import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.flow.Flow
import net.plumt.growth.update.internal.DefaultAppUpdater

/**
 * 업데이트 확인의 진입점. 원격 최소 버전([MinimumVersionSource])과 Play 상태를 합쳐 [UpdateAction] 을 정한다.
 *
 * ```kotlin
 * val updater = AppUpdater.create(context, minimumVersionSource = mySource)
 * val result = updater.check()                     // 앱 시작 시 한 번
 * if (result.isUpdateRequired) showBlockingScreen() // 강제면 Play 동작과 무관하게 본문을 막는다
 * when (val action = result.action) {
 *     is UpdateAction.BlockUntilStoreUpdate -> Unit             // 차단 화면의 버튼이 PlayStoreLink.open 을 부른다
 *     is UpdateAction.BlockUntilCheckSucceeds -> Unit           // 차단 화면의 버튼이 check() 를 다시 부른다
 *     UpdateAction.None -> Unit
 *     else -> updater.start(launcher, action)                   // Play 흐름
 * }
 * // ON_RESUME 마다: 즉시 업데이트 재개·백그라운드에서 끝난 다운로드를 잡는다
 * val again = updater.refresh()
 * ```
 *
 * 스레드: 메인 스레드에서 불러도 된다. [check] 는 원격 출처 → Play 순으로 순차 실행한다. Play 조회는 생성 시 정한
 * 시간 제한(기본 5초)을 넘으면 [PlayUpdateAvailability.Failed] 로 접는다. 원격 출처의 시간 제한은 출처 구현이 둔다.
 */
public interface AppUpdater {

    /** 원격 판정과 Play 상태를 확인해 다음 동작을 정한다. 실패는 결과 값에 담기고 예외로 던지지 않는다(취소 제외). */
    public suspend fun check(): UpdateCheckResult

    /**
     * Play 상태만 다시 보고 마지막 원격 판정과 합친다 — `onResume` 마다 부르는 용도다(원격 출처를 다시 부르지 않는다).
     * 즉시 업데이트가 진행 중이던 앱이 다시 열리면 [UpdateAction.ResumePlayImmediate], 유연 업데이트가 백그라운드에서
     * 다 내려받아졌으면 [UpdateAction.InstallDownloaded] 가 나온다. 아직 [check] 를 부르지 않았으면 [check] 와 같다.
     */
    public suspend fun refresh(): UpdateCheckResult

    /**
     * [action] 이 Play 로 처리하는 동작([UpdateAction.StartPlay]·[UpdateAction.ResumePlayImmediate]·[UpdateAction.InstallDownloaded])이면 실행한다.
     * 흐름이 끝날 때까지 기다리며, 그 사이 Activity 가 다시 만들어지면 결과를 잃는다 — 런처를 받는 [start] 를 권한다.
     *
     * @throws IllegalArgumentException [action] 이 Play 로 처리하는 동작이 아닐 때
     */
    public suspend fun start(activity: Activity, action: UpdateAction): PlayUpdateResult

    /**
     * [start] 와 같되 Activity Result API 로 흐름을 띄운다(Play 권장). Play 흐름은 [PlayUpdateResult.Launched] 를 곧바로 돌려주고
     * 사용자의 선택은 [launcher] 의 콜백으로 온다. [UpdateAction.InstallDownloaded] 는 런처가 필요 없어 설치를 요청한다.
     *
     * @throws IllegalArgumentException [action] 이 Play 로 처리하는 동작이 아닐 때
     */
    public suspend fun start(launcher: ActivityResultLauncher<IntentSenderRequest>, action: UpdateAction): PlayUpdateResult

    /** 유연 업데이트 진행 상태. 수집하는 동안만 Play 리스너가 등록된다. */
    public fun installStates(): Flow<PlayInstallState>

    public companion object {
        /** [create] 의 Play 조회 기본 시간 제한(밀리초). */
        public const val DEFAULT_PLAY_TIMEOUT_MILLIS: Long = 5_000L

        /**
         * @param minimumVersionSource 강제·권장 버전 출처. `null` 이면 Play 상태만 쓴다
         * @param playClient Play 래퍼. 기본은 실제 Play 인앱 업데이트
         * @param strategy 강제가 아닐 때 Play 방식을 정하는 전략
         * @param installed 설치된 버전. 기본은 패키지 정보에서 읽는다([InstalledVersion.of])
         * @param versionNameParser 설치 버전 이름 해석 규칙. 기본은 빌드 접미사를 무시하는 [VersionNameParser.NUMERIC_PREFIX]
         * @param onCheckFailure 원격 판정 실패 때 막을지. 기본은 막지 않는다([CheckFailurePolicy.FAIL_OPEN])
         * @param playTimeoutMillis Play 상태 조회의 시간 제한. 넘으면 [PlayUpdateAvailability.Failed]
         * @throws UpdateException [installed] 를 기본값으로 읽다 실패했을 때
         * @throws IllegalArgumentException [minimumVersionSource] 가 있는데 [versionNameParser] 가 설치 버전 이름을 해석하지 못할 때,
         *   [playTimeoutMillis] 가 0 이하일 때
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            context: Context,
            minimumVersionSource: MinimumVersionSource? = null,
            playClient: PlayUpdateClient = PlayUpdateClient.create(context),
            strategy: PlayUpdateStrategy = DefaultPlayUpdateStrategy(),
            installed: InstalledVersion = InstalledVersion.of(context),
            versionNameParser: VersionNameParser = VersionNameParser.NUMERIC_PREFIX,
            onCheckFailure: CheckFailurePolicy = CheckFailurePolicy.FAIL_OPEN,
            playTimeoutMillis: Long = DEFAULT_PLAY_TIMEOUT_MILLIS,
        ): AppUpdater {
            require(playTimeoutMillis > 0) { "playTimeoutMillis 는 0 보다 커야 합니다. 받은 값: $playTimeoutMillis" }
            return DefaultAppUpdater(
                force = minimumVersionSource?.let { ForceUpdateChecker.create(it, installed, versionNameParser) },
                playClient = playClient,
                strategy = strategy,
                onCheckFailure = onCheckFailure,
                playTimeoutMillis = playTimeoutMillis,
            )
        }
    }
}

/**
 * [AppUpdater.check] 의 결과.
 *
 * @property force 원격 판정. 출처가 없으면 [ForceUpdateDecision.UpToDate]
 * @property play Play 상태
 * @property action 둘을 합친 다음 동작
 */
public data class UpdateCheckResult(
    val force: ForceUpdateDecision,
    val play: PlayUpdateAvailability,
    val action: UpdateAction,
) {
    /**
     * 업데이트 전까지 앱 본문을 열면 안 되는지 — 강제 판정이거나 실패 정책이 막았을 때 `true`.
     * [action] 이 Play 동작(즉시 업데이트·재개·설치)이어도 이 값이 `true` 면 차단을 유지한다. Play 화면은 취소될 수 있다.
     */
    public val isBlocking: Boolean
        get() = force is ForceUpdateDecision.Required || action is UpdateAction.BlockUntilCheckSucceeds

    /** 원격 판정이 강제([ForceUpdateDecision.Required])인지. */
    public val isUpdateRequired: Boolean
        get() = force is ForceUpdateDecision.Required
}
