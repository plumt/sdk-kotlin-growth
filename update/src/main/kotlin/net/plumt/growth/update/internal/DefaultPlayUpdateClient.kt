package net.plumt.growth.update.internal

import android.app.Activity
import com.google.android.gms.tasks.Task
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallState
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import net.plumt.growth.update.PlayInstallState
import net.plumt.growth.update.PlayInstallStatus
import net.plumt.growth.update.PlayUpdateAvailability
import net.plumt.growth.update.PlayUpdateClient
import net.plumt.growth.update.PlayUpdateResult
import net.plumt.growth.update.PlayUpdateType
import net.plumt.growth.update.UpdateException

internal class DefaultPlayUpdateClient(
    private val manager: AppUpdateManager,
) : PlayUpdateClient {

    override suspend fun availability(): PlayUpdateAvailability = try {
        map(manager.appUpdateInfo.await())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        PlayUpdateAvailability.Failed(e as? UpdateException ?: UpdateException("Play 업데이트 정보를 가져오지 못했습니다.", e))
    }

    override suspend fun start(activity: Activity, type: PlayUpdateType): PlayUpdateResult = try {
        val info = manager.appUpdateInfo.await()
        val playType = type.toPlay()
        if (!info.isUpdateTypeAllowed(playType)) {
            throw UpdateException("이 기기에서 허용되지 않는 업데이트 방식입니다: $type")
        }
        val code = manager.startUpdateFlow(info, activity, AppUpdateOptions.newBuilder(playType).build()).await()
        when (code) {
            Activity.RESULT_OK -> PlayUpdateResult.Accepted
            Activity.RESULT_CANCELED -> PlayUpdateResult.Canceled
            else -> PlayUpdateResult.Failed(UpdateException("Play 업데이트 흐름이 실패했습니다. 결과 코드: $code"))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        PlayUpdateResult.Failed(e as? UpdateException ?: UpdateException("Play 업데이트 흐름을 시작하지 못했습니다.", e))
    }

    override fun installStates(): Flow<PlayInstallState> = callbackFlow {
        val listener = InstallStateUpdatedListener { state -> trySend(state.toModel()) }
        manager.registerListener(listener)
        awaitClose { manager.unregisterListener(listener) }
    }

    override suspend fun completeFlexibleUpdate(): PlayUpdateResult = try {
        manager.completeUpdate().await()
        PlayUpdateResult.Accepted
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        PlayUpdateResult.Failed(UpdateException("내려받은 업데이트를 설치하지 못했습니다.", e))
    }

    private fun map(info: AppUpdateInfo): PlayUpdateAvailability {
        // 설치 상태를 먼저 본다 — 내려받은 뒤에는 updateAvailability 가 UPDATE_AVAILABLE 로 남아 있을 수 있다
        when (info.installStatus()) {
            InstallStatus.DOWNLOADED -> return PlayUpdateAvailability.Downloaded
            InstallStatus.PENDING, InstallStatus.DOWNLOADING, InstallStatus.INSTALLING -> return PlayUpdateAvailability.Downloading
        }
        return when (info.updateAvailability()) {
            UpdateAvailability.UPDATE_AVAILABLE -> PlayUpdateAvailability.Available(
                availableVersionCode = info.availableVersionCode(),
                priority = info.updatePriority(),
                stalenessDays = info.clientVersionStalenessDays(),
                flexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
                immediateAllowed = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE),
            )
            UpdateAvailability.UPDATE_NOT_AVAILABLE -> PlayUpdateAvailability.NotAvailable
            UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> PlayUpdateAvailability.ImmediateInProgress
            else -> PlayUpdateAvailability.Unknown
        }
    }

    private fun PlayUpdateType.toPlay(): Int =
        if (this == PlayUpdateType.FLEXIBLE) AppUpdateType.FLEXIBLE else AppUpdateType.IMMEDIATE

    private fun InstallState.toModel(): PlayInstallState = PlayInstallState(
        status = when (installStatus()) {
            InstallStatus.PENDING -> PlayInstallStatus.PENDING
            InstallStatus.DOWNLOADING -> PlayInstallStatus.DOWNLOADING
            InstallStatus.DOWNLOADED -> PlayInstallStatus.DOWNLOADED
            InstallStatus.INSTALLING -> PlayInstallStatus.INSTALLING
            InstallStatus.INSTALLED -> PlayInstallStatus.INSTALLED
            InstallStatus.FAILED -> PlayInstallStatus.FAILED
            InstallStatus.CANCELED -> PlayInstallStatus.CANCELED
            else -> PlayInstallStatus.UNKNOWN
        },
        bytesDownloaded = bytesDownloaded(),
        totalBytesToDownload = totalBytesToDownload(),
        errorCode = installErrorCode(),
    )

    // 실패를 예외로 재개하면 코루틴의 스택 트레이스 복구가 예외를 복제해 cause 가 바뀐다 — Result 값으로 넘기고 직접 던진다
    private suspend fun <T> Task<T>.await(): T {
        val outcome: Result<T> = suspendCancellableCoroutine { cont ->
            addOnCompleteListener { task ->
                cont.resume(
                    if (task.isSuccessful) Result.success(task.result)
                    else Result.failure(task.exception ?: UpdateException("Play Task 가 원인 없이 실패했습니다.")),
                )
            }
        }
        return outcome.getOrThrow()
    }
}
