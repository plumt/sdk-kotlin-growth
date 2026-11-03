package net.plumt.growth.update

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.Flow
import net.plumt.growth.update.internal.DefaultAppUpdater

/**
 * 업데이트 확인의 진입점. 원격 최소 버전([MinimumVersionSource])과 Play 상태를 합쳐 [UpdateAction] 을 정한다.
 *
 * ```kotlin
 * val updater = AppUpdater.create(context, minimumVersionSource = mySource)
 * val result = updater.check()
 * when (val action = result.action) {
 *     is UpdateAction.BlockUntilStoreUpdate -> showBlockingScreen()      // update-compose 의 UpdateGate 를 써도 된다
 *     is UpdateAction.None -> Unit
 *     else -> updater.start(activity, action)
 * }
 * ```
 *
 * 스레드: 메인 스레드에서 불러도 된다. [check] 는 원격 출처 → Play 순으로 순차 실행하며 시간 제한은 두지 않는다
 * (출처 구현이 자체 타임아웃을 둔다).
 */
public interface AppUpdater {

    /** 원격 판정과 Play 상태를 확인해 다음 동작을 정한다. 실패는 결과 값에 담기고 예외로 던지지 않는다(취소 제외). */
    public suspend fun check(): UpdateCheckResult

    /**
     * [action] 이 Play 로 처리하는 동작([UpdateAction.StartPlay]·[UpdateAction.ResumePlayImmediate]·[UpdateAction.InstallDownloaded])이면 실행한다.
     *
     * @throws IllegalArgumentException [action] 이 Play 로 처리하는 동작이 아닐 때
     */
    public suspend fun start(activity: Activity, action: UpdateAction): PlayUpdateResult

    /** 유연 업데이트 진행 상태. 수집하는 동안만 Play 리스너가 등록된다. */
    public fun installStates(): Flow<PlayInstallState>

    public companion object {
        /**
         * @param minimumVersionSource 강제·권장 버전 출처. `null` 이면 Play 상태만 쓴다
         * @param playClient Play 래퍼. 기본은 실제 Play 인앱 업데이트
         * @param strategy 강제가 아닐 때 Play 방식을 정하는 전략
         * @param installed 설치된 버전. 기본은 패키지 정보에서 읽는다([InstalledVersion.of])
         * @throws UpdateException [installed] 를 기본값으로 읽다 실패했을 때
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            context: Context,
            minimumVersionSource: MinimumVersionSource? = null,
            playClient: PlayUpdateClient = PlayUpdateClient.create(context),
            strategy: PlayUpdateStrategy = DefaultPlayUpdateStrategy(),
            installed: InstalledVersion = InstalledVersion.of(context),
        ): AppUpdater = DefaultAppUpdater(
            force = minimumVersionSource?.let { ForceUpdateChecker.create(it, installed) },
            playClient = playClient,
            strategy = strategy,
        )
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
)
