package net.plumt.growth.update

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import com.google.android.play.core.install.model.AppUpdateType
import android.os.Looper
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.robolectric.Shadows.shadowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdaterTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val fake = FakeAppUpdateManager(context)
    private val installed = InstalledVersion("1.0.0", 10)

    private fun updater(source: MinimumVersionSource?, strategy: PlayUpdateStrategy = DefaultPlayUpdateStrategy()) = AppUpdater.create(
        context = context,
        minimumVersionSource = source,
        playClient = PlayUpdateClient.create(fake),
        strategy = strategy,
        installed = installed,
    )

    private fun accept(block: suspend () -> PlayUpdateResult): PlayUpdateResult {
        var acted = false
        return runWithLooper(onTick = { if (!acted && fake.isConfirmationDialogVisible) { acted = true; fake.userAcceptsUpdate() } }, block = block)
    }

    @Test
    fun `출처 없이 Play 만 쓰면 우선순위로 방식을 고른다`() {
        fake.setUpdateAvailable(11)
        fake.setUpdatePriority(5)
        val result = runWithLooper { updater(null).check() }
        assertEquals(ForceUpdateDecision.UpToDate, result.force)
        assertEquals(UpdateAction.StartPlay(PlayUpdateType.IMMEDIATE), result.action)
        assertTrue(result.play is PlayUpdateAvailability.Available)
    }

    @Test
    fun `낮은 우선순위는 유연 업데이트`() {
        fake.setUpdateAvailable(11)
        fake.setUpdatePriority(1)
        assertEquals(UpdateAction.StartPlay(PlayUpdateType.FLEXIBLE), runWithLooper { updater(null).check() }.action)
    }

    @Test
    fun `교체한 전략을 쓴다`() {
        fake.setUpdateAvailable(11)
        assertEquals(UpdateAction.None, runWithLooper { updater(null, strategy = PlayUpdateStrategy { null }).check() }.action)
    }

    @Test
    fun `새 버전이 없으면 None`() {
        fake.setUpdateNotAvailable()
        assertEquals(UpdateAction.None, runWithLooper { updater(null).check() }.action)
    }

    @Test
    fun `강제 판정과 Play 가 있으면 즉시 업데이트`() {
        fake.setUpdateAvailable(11)
        val result = runWithLooper { updater(MinimumVersionSource { VersionRequirement(minimumVersionName = "1.1.0") }).check() }
        assertTrue(result.force is ForceUpdateDecision.Required)
        assertEquals(UpdateAction.StartPlay(PlayUpdateType.IMMEDIATE), result.action)
    }

    @Test
    fun `강제인데 Play 가 즉시를 허용하지 않으면 스토어 차단 동작`() {
        fake.setUpdateAvailable(11, AppUpdateType.FLEXIBLE)
        val result = runWithLooper { updater(MinimumVersionSource { VersionRequirement(minimumVersionCode = 11) }).check() }
        assertTrue(result.action is UpdateAction.BlockUntilStoreUpdate)
    }

    @Test
    fun `출처가 실패해도 Play 결과로 진행하고 실패는 결과에 남는다`() {
        fake.setUpdateAvailable(11, AppUpdateType.FLEXIBLE)
        val result = runWithLooper { updater(MinimumVersionSource { throw java.io.IOException("오프라인") }).check() }
        assertTrue(result.force is ForceUpdateDecision.CheckFailed)
        assertEquals(UpdateAction.StartPlay(PlayUpdateType.FLEXIBLE), result.action)
    }

    @Test
    fun `start 는 StartPlay 를 실행한다`() {
        fake.setUpdateAvailable(11)
        val r = accept { updater(null).start(activity, UpdateAction.StartPlay(PlayUpdateType.FLEXIBLE)) }
        assertEquals(PlayUpdateResult.Accepted, r)
    }

    @Test
    fun `start 는 ResumePlayImmediate 를 즉시 방식으로 실행한다`() {
        fake.setUpdateAvailable(11, AppUpdateType.IMMEDIATE)
        val r = accept { updater(null).start(activity, UpdateAction.ResumePlayImmediate) }
        assertEquals(PlayUpdateResult.Accepted, r)
    }

    @Test
    fun `start 는 InstallDownloaded 로 설치를 요청한다`() {
        fake.setUpdateAvailable(11)
        accept { updater(null).start(activity, UpdateAction.StartPlay(PlayUpdateType.FLEXIBLE)) }
        fake.downloadStarts()
        fake.downloadCompletes()
        assertEquals(PlayUpdateResult.Accepted, runWithLooper { updater(null).start(activity, UpdateAction.InstallDownloaded) })
        assertTrue(fake.isInstallSplashScreenVisible)
    }

    @Test
    fun `Play 로 처리하지 않는 동작을 start 에 넘기면 IllegalArgumentException`() {
        val u = updater(null)
        val none = assertThrows(IllegalArgumentException::class.java) { runWithLooper { u.start(activity, UpdateAction.None) } }
        assertTrue(none.message!!.contains("None"))
        val required = ForceUpdateDecision.Required(installed, "2.0.0", null)
        assertThrows(IllegalArgumentException::class.java) {
            runWithLooper { u.start(activity, UpdateAction.BlockUntilStoreUpdate(required)) }
        }
    }

    @Test
    fun `installStates 는 Play 클라이언트의 Flow 를 그대로 노출한다`() {
        fake.setUpdateAvailable(11)
        val collected = mutableListOf<PlayInstallState>()
        runWithLooper { updater(null).start(activity, UpdateAction.StartPlay(PlayUpdateType.FLEXIBLE)) }
        runBlocking {
            val job = launch(start = CoroutineStart.UNDISPATCHED) { updater(null).installStates().toList(collected) }
            yield()
            fake.userAcceptsUpdate()
            fake.downloadStarts()
            shadowOf(Looper.getMainLooper()).idle()
            yield(); yield()
            job.cancelAndJoin()
        }
        assertEquals(PlayInstallStatus.PENDING, collected.first().status)
    }

    @Test
    fun `스토어 링크 인텐트는 이 앱의 패키지를 가리킨다`() {
        assertEquals("market://details?id=${context.packageName}", PlayStoreLink.intent(context).data.toString())
        assertEquals(
            "https://play.google.com/store/apps/details?id=${context.packageName}",
            PlayStoreLink.webIntent(context).data.toString(),
        )
        assertTrue(PlayStoreLink.intent(context).flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
