package net.plumt.growth.update

import android.app.Activity
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import com.google.android.play.core.install.model.AppUpdateType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Play 의 FakeAppUpdateManager 로 PlayUpdateClient 의 모든 흐름을 검증한다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayUpdateClientTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val fake = FakeAppUpdateManager(context)
    private val client = PlayUpdateClient.create(fake)

    @Test
    fun `새 버전이 없으면 NotAvailable`() {
        fake.setUpdateNotAvailable()
        assertEquals(PlayUpdateAvailability.NotAvailable, runWithLooper { client.availability() })
    }

    @Test
    fun `새 버전이 있으면 코드 우선순위 경과일 허용 방식을 담는다`() {
        fake.setUpdateAvailable(42, AppUpdateType.FLEXIBLE)
        fake.setUpdatePriority(3)
        fake.setClientVersionStalenessDays(9)
        assertEquals(
            PlayUpdateAvailability.Available(42, 3, 9, flexibleAllowed = true, immediateAllowed = false),
            runWithLooper { client.availability() },
        )
    }

    @Test
    fun `즉시만 허용되면 flexible 은 false`() {
        fake.setUpdateAvailable(42, AppUpdateType.IMMEDIATE)
        val a = runWithLooper { client.availability() } as PlayUpdateAvailability.Available
        assertTrue(!a.flexibleAllowed && a.immediateAllowed)
        assertTrue(a.allows(PlayUpdateType.IMMEDIATE) && !a.allows(PlayUpdateType.FLEXIBLE))
    }

    @Test
    fun `두 방식이 모두 허용되면 둘 다 true 이고 경과일은 기본 null 이다`() {
        fake.setUpdateAvailable(7)
        val a = runWithLooper { client.availability() } as PlayUpdateAvailability.Available
        assertTrue(a.flexibleAllowed && a.immediateAllowed)
        assertNull(a.stalenessDays)
    }

    @Test
    fun `유연 업데이트 전체 흐름 - 수락 내려받기 완료 설치`() {
        fake.setUpdateAvailable(5)
        assertEquals(PlayUpdateResult.Accepted, runWithLooper { client.start(activity, PlayUpdateType.FLEXIBLE) })
        assertTrue(fake.isConfirmationDialogVisible)
        fake.userAcceptsUpdate()

        fake.downloadStarts()
        assertEquals(PlayUpdateAvailability.Downloading, runWithLooper { client.availability() })
        fake.downloadCompletes()
        assertEquals(PlayUpdateAvailability.Downloaded, runWithLooper { client.availability() })

        assertEquals(PlayUpdateResult.Accepted, runWithLooper { client.completeFlexibleUpdate() })
        assertTrue(fake.isInstallSplashScreenVisible)
    }

    // Play 의 Fake 는 흐름 Task 를 항상 RESULT_OK 로 즉시 끝내므로 취소 결과는 대역으로 만든다
    @Test
    fun `사용자가 취소하면 Canceled`() {
        fake.setUpdateAvailable(5)
        val canceling = PlayUpdateClient.create(object : AppUpdateManager by fake {
            override fun startUpdateFlow(info: AppUpdateInfo, activity: Activity, options: AppUpdateOptions) =
                Tasks.forResult(Activity.RESULT_CANCELED)
        })
        assertEquals(PlayUpdateResult.Canceled, runWithLooper { canceling.start(activity, PlayUpdateType.FLEXIBLE) })
        assertEquals(PlayUpdateResult.Canceled, runWithLooper { canceling.start(activity, PlayUpdateType.IMMEDIATE) })
    }

    @Test
    fun `즉시 업데이트를 시작하면 Accepted 이고 즉시 흐름 화면이 뜬다`() {
        fake.setUpdateAvailable(5, AppUpdateType.IMMEDIATE)
        assertEquals(PlayUpdateResult.Accepted, runWithLooper { client.start(activity, PlayUpdateType.IMMEDIATE) })
        assertTrue(fake.isImmediateFlowVisible)
    }

    @Test
    fun `유연 업데이트를 시작하면 확인 창이 뜬다`() {
        fake.setUpdateAvailable(5)
        assertEquals(PlayUpdateResult.Accepted, runWithLooper { client.start(activity, PlayUpdateType.FLEXIBLE) })
        assertTrue(fake.isConfirmationDialogVisible)
    }

    @Test
    fun `허용되지 않는 방식을 시작하면 Failed 이고 메시지에 방식이 있다`() {
        fake.setUpdateAvailable(5, AppUpdateType.FLEXIBLE)
        val r = runWithLooper { client.start(activity, PlayUpdateType.IMMEDIATE) } as PlayUpdateResult.Failed
        assertTrue(r.cause.message!!.contains("IMMEDIATE"))
    }

    @Test
    fun `새 버전이 없을 때 시작하면 Failed`() {
        fake.setUpdateNotAvailable()
        assertTrue(runWithLooper { client.start(activity, PlayUpdateType.FLEXIBLE) } is PlayUpdateResult.Failed)
    }

    @Test
    fun `Play 가 정보를 주지 못하면 Failed 에 원인이 보존된다`() {
        val boom = java.io.IOException("Play 없음")
        val broken = PlayUpdateClient.create(object : AppUpdateManager by fake {
            override fun getAppUpdateInfo() = Tasks.forException<AppUpdateInfo>(boom)
        })
        val a = runWithLooper { broken.availability() } as PlayUpdateAvailability.Failed
        assertSame(boom, a.cause.cause)
        val s = runWithLooper { broken.start(activity, PlayUpdateType.FLEXIBLE) } as PlayUpdateResult.Failed
        assertSame(boom, s.cause.cause)
    }

    @Test
    fun `흐름 시작이 코드 없이 실패하면 Failed`() {
        fake.setUpdateAvailable(5)
        val bad = PlayUpdateClient.create(object : AppUpdateManager by fake {
            override fun startUpdateFlow(info: AppUpdateInfo, activity: Activity, options: AppUpdateOptions) =
                Tasks.forResult(1) // ActivityResult.RESULT_IN_APP_UPDATE_FAILED
        })
        val r = runWithLooper { bad.start(activity, PlayUpdateType.FLEXIBLE) } as PlayUpdateResult.Failed
        assertTrue(r.cause.message!!.contains("1"))
    }

    @Test
    fun `흐름 Task 가 예외로 끝나면 Failed 에 원인이 보존된다`() {
        fake.setUpdateAvailable(5)
        val boom = IllegalStateException("흐름 오류")
        val bad = PlayUpdateClient.create(object : AppUpdateManager by fake {
            override fun startUpdateFlow(info: AppUpdateInfo, activity: Activity, options: AppUpdateOptions) =
                Tasks.forException<Int>(boom)
        })
        val r = runWithLooper { bad.start(activity, PlayUpdateType.FLEXIBLE) } as PlayUpdateResult.Failed
        assertSame(boom, r.cause.cause)
    }

    @Test
    fun `설치 요청이 실패하면 Failed 에 원인이 보존된다`() {
        val boom = IllegalStateException("설치 오류")
        val bad = PlayUpdateClient.create(object : AppUpdateManager by fake {
            override fun completeUpdate() = Tasks.forException<Void>(boom)
        })
        val r = runWithLooper { bad.completeFlexibleUpdate() } as PlayUpdateResult.Failed
        assertSame(boom, r.cause.cause)
    }

    @Test
    fun `취소는 결과로 접지 않고 전파한다`() {
        val never = PlayUpdateClient.create(object : AppUpdateManager by fake {
            override fun getAppUpdateInfo() = com.google.android.gms.tasks.TaskCompletionSource<AppUpdateInfo>().task
        })
        runBlocking {
            val job = async(start = CoroutineStart.UNDISPATCHED) { never.availability() }
            yield()
            job.cancel()
            assertThrows(CancellationException::class.java) { runBlocking { job.await() } }
        }
    }

    @Test
    fun `설치 상태 Flow 가 진행률 크기 상태를 순서대로 전달한다`() {
        fake.setUpdateAvailable(5)
        fake.setTotalBytesToDownload(200)
        val collected = mutableListOf<PlayInstallState>()
        runWithLooper { client.start(activity, PlayUpdateType.FLEXIBLE) }
        runBlocking {
            val job = launch(start = CoroutineStart.UNDISPATCHED) { client.installStates().toList(collected) }
            yield() // 생산자 코루틴이 리스너를 등록할 때까지
            // 리스너가 등록된 뒤에 상태를 흘린다
            fake.userAcceptsUpdate()
            fake.downloadStarts()
            fake.setBytesDownloaded(50)
            fake.downloadCompletes()
            shadowOf(Looper.getMainLooper()).idle()
            yield(); yield()
            job.cancelAndJoin()
        }
        val statuses = collected.map { it.status }
        assertEquals(listOf(PlayInstallStatus.PENDING, PlayInstallStatus.DOWNLOADING, PlayInstallStatus.DOWNLOADED), statuses)
        assertEquals(PlayInstallStatus.DOWNLOADED, statuses.last())
    }

    @Test
    fun `Flow 수집을 끝내면 리스너를 해제해 이후 상태를 받지 않는다`() {
        fake.setUpdateAvailable(5)
        val collected = mutableListOf<PlayInstallState>()
        runWithLooper { client.start(activity, PlayUpdateType.FLEXIBLE) }
        runBlocking {
            val job = launch(start = CoroutineStart.UNDISPATCHED) { client.installStates().toList(collected) }
            yield() // 생산자 코루틴이 리스너를 등록할 때까지
            job.cancelAndJoin()
            fake.userAcceptsUpdate()
            fake.downloadStarts()
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue(collected.isEmpty())
    }

    @Test
    fun `내려받기 실패는 FAILED 와 오류 코드를 전달한다`() {
        fake.setUpdateAvailable(5)
        val collected = mutableListOf<PlayInstallState>()
        runWithLooper { client.start(activity, PlayUpdateType.FLEXIBLE) }
        runBlocking {
            val job = launch(start = CoroutineStart.UNDISPATCHED) { client.installStates().toList(collected) }
            yield() // 생산자 코루틴이 리스너를 등록할 때까지
            fake.userAcceptsUpdate()
            fake.downloadStarts()
            fake.setInstallErrorCode(com.google.android.play.core.install.model.InstallErrorCode.ERROR_INSTALL_NOT_ALLOWED)
            fake.downloadFails()
            shadowOf(Looper.getMainLooper()).idle()
            yield(); yield()
            job.cancelAndJoin()
        }
        val last = collected.last()
        assertEquals(PlayInstallStatus.FAILED, last.status)
        assertEquals(com.google.android.play.core.install.model.InstallErrorCode.ERROR_INSTALL_NOT_ALLOWED, last.errorCode)
    }

    @Test
    fun `진행률은 전체 크기를 알 때만 계산하고 0 에서 1 사이로 자른다`() {
        assertEquals(0.25f, PlayInstallState(PlayInstallStatus.DOWNLOADING, 50, 200).progress)
        assertNull(PlayInstallState(PlayInstallStatus.PENDING).progress)
        assertEquals(1f, PlayInstallState(PlayInstallStatus.DOWNLOADING, 300, 200).progress)
        assertEquals(0f, PlayInstallState(PlayInstallStatus.DOWNLOADING, 0, 200).progress)
    }

    private fun info(availability: Int, install: Int = 0, priority: Int = 2, staleness: Int? = 4): AppUpdateInfo {
        val pi = android.app.PendingIntent.getActivity(context, 0, android.content.Intent(), android.app.PendingIntent.FLAG_IMMUTABLE)
        val zzb = AppUpdateInfo::class.java.methods.first { it.name == "zzb" && java.lang.reflect.Modifier.isStatic(it.modifiers) }
        return zzb.invoke(null, context.packageName, 77, availability, install, staleness, priority, 0L, 0L, 0L, 0L, pi, pi, pi, pi, emptyMap<Any, Any>()) as AppUpdateInfo
    }

    private fun availabilityOf(info: AppUpdateInfo): PlayUpdateAvailability {
        val c = PlayUpdateClient.create(object : AppUpdateManager by fake {
            override fun getAppUpdateInfo() = Tasks.forResult(info)
        })
        return runWithLooper { c.availability() }
    }

    @Test
    fun `Play 상태 코드 매핑 - 가용성`() {
        assertEquals(PlayUpdateAvailability.Unknown, availabilityOf(info(0)))
        assertEquals(PlayUpdateAvailability.NotAvailable, availabilityOf(info(1)))
        assertEquals(PlayUpdateAvailability.ImmediateInProgress, availabilityOf(info(3)))
        val a = availabilityOf(info(2, priority = 4, staleness = 12)) as PlayUpdateAvailability.Available
        assertEquals(77, a.availableVersionCode)
        assertEquals(4, a.priority)
        assertEquals(12, a.stalenessDays)
        assertEquals(PlayUpdateAvailability.Unknown, availabilityOf(info(99)))
    }

    @Test
    fun `Play 상태 코드 매핑 - 설치 상태가 가용성보다 먼저`() {
        assertEquals(PlayUpdateAvailability.Downloaded, availabilityOf(info(2, install = 11)))
        listOf(1, 2, 3).forEach { assertEquals(PlayUpdateAvailability.Downloading, availabilityOf(info(2, install = it))) }
        // 설치 완료·실패·취소는 가용성 코드를 그대로 따른다
        listOf(4, 5, 6).forEach { assertTrue(availabilityOf(info(2, install = it)) is PlayUpdateAvailability.Available) }
    }
}
