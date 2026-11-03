package net.plumt.growth.update.internal

import android.app.Activity
import kotlinx.coroutines.flow.Flow
import net.plumt.growth.update.AppUpdater
import net.plumt.growth.update.ForceUpdateChecker
import net.plumt.growth.update.ForceUpdateDecision
import net.plumt.growth.update.PlayInstallState
import net.plumt.growth.update.PlayUpdateClient
import net.plumt.growth.update.PlayUpdateResult
import net.plumt.growth.update.PlayUpdateStrategy
import net.plumt.growth.update.PlayUpdateType
import net.plumt.growth.update.UpdateAction
import net.plumt.growth.update.UpdateActionResolver
import net.plumt.growth.update.UpdateCheckResult

internal class DefaultAppUpdater(
    private val force: ForceUpdateChecker?,
    private val playClient: PlayUpdateClient,
    private val strategy: PlayUpdateStrategy,
) : AppUpdater {

    override suspend fun check(): UpdateCheckResult {
        val decision = force?.check() ?: ForceUpdateDecision.UpToDate
        val play = playClient.availability()
        return UpdateCheckResult(decision, play, UpdateActionResolver.resolve(decision, play, strategy))
    }

    override suspend fun start(activity: Activity, action: UpdateAction): PlayUpdateResult = when (action) {
        is UpdateAction.StartPlay -> playClient.start(activity, action.type)
        UpdateAction.ResumePlayImmediate -> playClient.start(activity, PlayUpdateType.IMMEDIATE)
        UpdateAction.InstallDownloaded -> playClient.completeFlexibleUpdate()
        UpdateAction.None, is UpdateAction.BlockUntilStoreUpdate ->
            throw IllegalArgumentException("Play 로 처리하는 동작이 아닙니다. 받은 값: $action")
    }

    override fun installStates(): Flow<PlayInstallState> = playClient.installStates()
}
