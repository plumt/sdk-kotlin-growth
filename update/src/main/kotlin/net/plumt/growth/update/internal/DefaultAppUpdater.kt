package net.plumt.growth.update.internal

import android.app.Activity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull
import net.plumt.growth.update.AppUpdater
import net.plumt.growth.update.CheckFailurePolicy
import net.plumt.growth.update.ForceUpdateChecker
import net.plumt.growth.update.ForceUpdateDecision
import net.plumt.growth.update.PlayInstallState
import net.plumt.growth.update.PlayUpdateAvailability
import net.plumt.growth.update.PlayUpdateClient
import net.plumt.growth.update.PlayUpdateResult
import net.plumt.growth.update.PlayUpdateStrategy
import net.plumt.growth.update.PlayUpdateType
import net.plumt.growth.update.UpdateAction
import net.plumt.growth.update.UpdateActionResolver
import net.plumt.growth.update.UpdateCheckResult
import net.plumt.growth.update.UpdateException

internal class DefaultAppUpdater(
    private val force: ForceUpdateChecker?,
    private val playClient: PlayUpdateClient,
    private val strategy: PlayUpdateStrategy,
    private val onCheckFailure: CheckFailurePolicy,
    private val playTimeoutMillis: Long,
) : AppUpdater {

    @Volatile
    private var lastDecision: ForceUpdateDecision? = null

    override suspend fun check(): UpdateCheckResult {
        val decision = force?.check() ?: ForceUpdateDecision.UpToDate
        lastDecision = decision
        return combine(decision)
    }

    override suspend fun refresh(): UpdateCheckResult = lastDecision?.let { combine(it) } ?: check()

    private suspend fun combine(decision: ForceUpdateDecision): UpdateCheckResult {
        val play = withTimeoutOrNull(playTimeoutMillis) { playClient.availability() }
            ?: PlayUpdateAvailability.Failed(UpdateException("Play 업데이트 정보 조회가 ${playTimeoutMillis}ms 안에 끝나지 않았습니다."))
        return UpdateCheckResult(decision, play, UpdateActionResolver.resolve(decision, play, strategy, onCheckFailure))
    }

    override suspend fun start(activity: Activity, action: UpdateAction): PlayUpdateResult = when (action) {
        is UpdateAction.StartPlay -> playClient.start(activity, action.type)
        UpdateAction.ResumePlayImmediate -> playClient.start(activity, PlayUpdateType.IMMEDIATE)
        UpdateAction.InstallDownloaded -> playClient.completeFlexibleUpdate()
        else -> throw notPlay(action)
    }

    override suspend fun start(launcher: ActivityResultLauncher<IntentSenderRequest>, action: UpdateAction): PlayUpdateResult =
        when (action) {
            is UpdateAction.StartPlay -> playClient.start(launcher, action.type)
            UpdateAction.ResumePlayImmediate -> playClient.start(launcher, PlayUpdateType.IMMEDIATE)
            UpdateAction.InstallDownloaded -> playClient.completeFlexibleUpdate()
            else -> throw notPlay(action)
        }

    private fun notPlay(action: UpdateAction) = IllegalArgumentException("Play 로 처리하는 동작이 아닙니다. 받은 값: $action")

    override fun installStates(): Flow<PlayInstallState> = playClient.installStates()
}
