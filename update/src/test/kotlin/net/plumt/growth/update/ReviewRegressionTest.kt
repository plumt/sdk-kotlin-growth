package net.plumt.growth.update

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 리뷰 보고서의 결함을 재현하는 회귀 테스트. 이름 앞의 기호는 보고서 항목이다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewRegressionTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val fake = FakeAppUpdateManager(context)

    @Test
    fun `H2 권장 버전 키가 비어 있어도 최소 버전 강제는 살아 있다`() = runBlocking {
        val source = MinimumVersionSource { VersionRequirement(minimumVersionName = "1.1.0", recommendedVersionName = "") }
        val d = ForceUpdateChecker.create(source, InstalledVersion("1.0.0", 1)).check()
        assertTrue("받은 값: $d", d is ForceUpdateDecision.Required)
    }

    @Test
    fun `H2 빈 최소 버전은 설정 없음이다`() {
        assertEquals(ForceUpdateDecision.UpToDate, VersionRequirement(minimumVersionName = "  ").evaluate(InstalledVersion("1.0.0", 1)))
    }

    @Test
    fun `H3 빌드 접미사가 붙은 정식 빌드는 같은 최소 버전에 막히지 않는다`() {
        val d = VersionRequirement(minimumVersionName = "1.4.0").evaluate(InstalledVersion("1.4.0-prod", 1))
        assertEquals(ForceUpdateDecision.UpToDate, d)
    }

    @Test
    fun `H3 공백과 괄호가 붙은 이름도 숫자 부분으로 비교한다`() {
        val d = VersionRequirement(minimumVersionName = "1.5.0").evaluate(InstalledVersion("1.4.0 (45)", 1))
        assertTrue("받은 값: $d", d is ForceUpdateDecision.Required)
    }

    @Test
    fun `L4 사전 릴리스 숫자 식별자의 선행 0 은 거부한다`() {
        assertThrows(IllegalArgumentException::class.java) { SemanticVersion.parse("1.0.0-01") }
    }

    @Test
    fun `L7 스토어 인텐트는 Play 스토어 앱을 지정한다`() {
        assertEquals("com.android.vending", PlayStoreLink.intent(context).`package`)
    }

    @Test
    fun `M5 재개 경로는 허용 검사 없이 즉시 흐름을 다시 띄운다`() {
        val pi: android.app.PendingIntent? = null
        val zzb = AppUpdateInfo::class.java.methods.first { it.name == "zzb" && java.lang.reflect.Modifier.isStatic(it.modifiers) }
        // DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS(3) 이면서 어떤 방식도 허용되지 않는 정보
        val inProgress = zzb.invoke(null, context.packageName, 77, 3, 0, null, 0, 0L, 0L, 0L, 0L, pi, pi, pi, pi, emptyMap<Any, Any>()) as AppUpdateInfo
        val client = PlayUpdateClient.create(object : AppUpdateManager by fake {
            override fun getAppUpdateInfo() = Tasks.forResult(inProgress)
            override fun startUpdateFlow(info: AppUpdateInfo, activity: Activity, options: AppUpdateOptions) =
                Tasks.forResult(Activity.RESULT_OK)
        })
        assertEquals(PlayUpdateResult.Accepted, runWithLooper { client.start(activity, PlayUpdateType.IMMEDIATE) })
    }

    // ---- H2: 실패 정책 ----

    private val offline = ForceUpdateDecision.CheckFailed(UpdateException("오프라인"), CheckFailureReason.SOURCE_UNAVAILABLE)
    private val badValue = ForceUpdateDecision.CheckFailed(UpdateException("v2"), CheckFailureReason.INVALID_REQUIREMENT)
    private val badInstalled = ForceUpdateDecision.CheckFailed(UpdateException("dev"), CheckFailureReason.INVALID_INSTALLED_VERSION)
    private val available = PlayUpdateAvailability.Available(2, 0, null, flexibleAllowed = true, immediateAllowed = true)

    @Test
    fun `H2 실패 정책 상수의 표`() {
        listOf(offline, badValue, badInstalled).forEach {
            assertEquals(false, CheckFailurePolicy.FAIL_OPEN.shouldBlock(it))
            assertEquals(true, CheckFailurePolicy.FAIL_CLOSED.shouldBlock(it))
        }
        assertEquals(false, CheckFailurePolicy.FAIL_CLOSED_ON_INVALID_VALUE.shouldBlock(offline))
        assertEquals(true, CheckFailurePolicy.FAIL_CLOSED_ON_INVALID_VALUE.shouldBlock(badValue))
        assertEquals(true, CheckFailurePolicy.FAIL_CLOSED_ON_INVALID_VALUE.shouldBlock(badInstalled))
    }

    @Test
    fun `H2 결정표 0번 줄 - 정책이 막으면 Play 상태와 무관하게 다시 확인할 때까지 막는다`() {
        val plays = listOf(
            available, PlayUpdateAvailability.ImmediateInProgress, PlayUpdateAvailability.Downloaded, PlayUpdateAvailability.NotAvailable,
        )
        plays.forEach { play ->
            assertEquals(
                UpdateAction.BlockUntilCheckSucceeds(badValue),
                UpdateActionResolver.resolve(badValue, play, onCheckFailure = CheckFailurePolicy.FAIL_CLOSED_ON_INVALID_VALUE),
            )
            val r = UpdateCheckResult(badValue, play, UpdateAction.BlockUntilCheckSucceeds(badValue))
            assertTrue(r.isBlocking)
            assertTrue(!r.isUpdateRequired)
        }
        // 막지 않는 실패와 기본 정책은 기존 fail-open
        assertEquals(UpdateAction.StartPlay(PlayUpdateType.FLEXIBLE), UpdateActionResolver.resolve(offline, available, onCheckFailure = CheckFailurePolicy.FAIL_CLOSED_ON_INVALID_VALUE))
        assertEquals(UpdateAction.StartPlay(PlayUpdateType.FLEXIBLE), UpdateActionResolver.resolve(badValue, available))
        assertTrue(!UpdateCheckResult(offline, available, UpdateAction.None).isBlocking)
    }

    @Test
    fun `H2 AppUpdater 에 넘긴 실패 정책을 결정표에 쓴다`() {
        fake.setUpdateAvailable(11)
        val u = AppUpdater.create(
            context, MinimumVersionSource { VersionRequirement(minimumVersionName = "v2") }, PlayUpdateClient.create(fake),
            installed = InstalledVersion("1.0.0", 1), onCheckFailure = CheckFailurePolicy.FAIL_CLOSED_ON_INVALID_VALUE,
        )
        val r = runWithLooper { u.check() }
        assertTrue("받은 값: ${r.action}", r.action is UpdateAction.BlockUntilCheckSucceeds)
        assertEquals(CheckFailureReason.INVALID_REQUIREMENT, (r.force as ForceUpdateDecision.CheckFailed).reason)
        assertThrows(IllegalArgumentException::class.java) { runWithLooper { u.start(activity, r.action) } }
    }

    // ---- H3: 생성 시점 검사 ----

    @Test
    fun `H3 AppUpdater 는 설치 버전 이름을 해석하지 못하면 생성 시 IllegalArgumentException`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            AppUpdater.create(context, MinimumVersionSource { VersionRequirement.NONE }, PlayUpdateClient.create(fake), installed = InstalledVersion("nightly", 1))
        }
        assertTrue(e.message!!.contains("nightly"))
        // 출처가 없으면 이름을 쓰지 않으니 검사하지 않는다
        AppUpdater.create(context, null, PlayUpdateClient.create(fake), installed = InstalledVersion("nightly", 1))
        // 규칙을 바꾸면 통과한다
        AppUpdater.create(
            context, MinimumVersionSource { VersionRequirement.NONE }, PlayUpdateClient.create(fake),
            installed = InstalledVersion("nightly", 1), versionNameParser = { SemanticVersion.parse("0") },
        )
    }

    @Test
    fun `H3 AppUpdater 기본 규칙으로 접미사 빌드가 막히지 않는다`() {
        fake.setUpdateNotAvailable()
        val u = AppUpdater.create(
            context, MinimumVersionSource { VersionRequirement(minimumVersionName = "1.4.0") }, PlayUpdateClient.create(fake),
            installed = InstalledVersion("1.4.0-prod", 40),
        )
        val r = runWithLooper { u.check() }
        assertEquals(ForceUpdateDecision.UpToDate, r.force)
        assertTrue(!r.isBlocking)
    }

    // ---- M2/L7: 스토어 링크 ----

    @Test
    fun `M2 스토어 앱이 있으면 Play 스토어 앱으로 연다`() {
        val shadowApp = org.robolectric.Shadows.shadowOf(context)
        shadowApp.checkActivities(true)
        val pm = org.robolectric.Shadows.shadowOf(context.packageManager)
        pm.addActivityIfNotPresent(android.content.ComponentName(PlayStoreLink.PLAY_STORE_PACKAGE, "Store"))
        pm.addIntentFilterForActivity(
            android.content.ComponentName(PlayStoreLink.PLAY_STORE_PACKAGE, "Store"),
            android.content.IntentFilter(android.content.Intent.ACTION_VIEW).apply { addDataScheme("market"); addCategory(android.content.Intent.CATEGORY_DEFAULT) },
        )
        PlayStoreLink.open(context)
        val started = shadowApp.nextStartedActivity
        assertEquals("market://details?id=${context.packageName}", started.dataString)
        assertEquals(PlayStoreLink.PLAY_STORE_PACKAGE, started.`package`)
    }

    @Test
    fun `M2 스토어 앱이 없으면 웹으로 연다`() {
        val shadowApp = org.robolectric.Shadows.shadowOf(context)
        shadowApp.checkActivities(true)
        val pm = org.robolectric.Shadows.shadowOf(context.packageManager)
        val browser = android.content.ComponentName("browser.example", "Browser")
        pm.addActivityIfNotPresent(browser)
        pm.addIntentFilterForActivity(
            browser,
            android.content.IntentFilter(android.content.Intent.ACTION_VIEW).apply { addDataScheme("https"); addCategory(android.content.Intent.CATEGORY_DEFAULT) },
        )
        PlayStoreLink.open(context)
        assertEquals("https://play.google.com/store/apps/details?id=${context.packageName}", shadowApp.nextStartedActivity.dataString)
    }

    @Test
    fun `M2 둘 다 없으면 크래시 대신 UpdateException 이고 원인을 보존한다`() {
        org.robolectric.Shadows.shadowOf(context).checkActivities(true)
        val e = assertThrows(UpdateException::class.java) { PlayStoreLink.open(context) }
        assertTrue(e.cause is android.content.ActivityNotFoundException)
    }

    // ---- M3: refresh ----

    private class CountingSource(var requirement: VersionRequirement) : MinimumVersionSource {
        var calls = 0
        override suspend fun fetch(): VersionRequirement { calls++; return requirement }
    }

    @Test
    fun `M3 refresh 는 원격 출처를 다시 부르지 않고 Play 상태만 다시 본다`() {
        val source = CountingSource(VersionRequirement(minimumVersionName = "2.0"))
        val u = AppUpdater.create(context, source, PlayUpdateClient.create(fake), installed = InstalledVersion("1.0", 1))
        fake.setUpdateAvailable(11, com.google.android.play.core.install.model.AppUpdateType.FLEXIBLE)
        assertTrue(runWithLooper { u.check() }.action is UpdateAction.BlockUntilStoreUpdate)
        // 백그라운드에서 유연 업데이트가 끝까지 내려받아졌다
        runWithLooper { u.start(activity, UpdateAction.StartPlay(PlayUpdateType.FLEXIBLE)) }
        fake.userAcceptsUpdate(); fake.downloadStarts(); fake.downloadCompletes()
        val again = runWithLooper { u.refresh() }
        assertEquals(1, source.calls)
        assertEquals(UpdateAction.InstallDownloaded, again.action)
        assertTrue("강제이므로 설치 동작이어도 차단 유지", again.isBlocking)
    }

    @Test
    fun `M3 check 전에 refresh 를 부르면 check 와 같다`() {
        val source = CountingSource(VersionRequirement.NONE)
        fake.setUpdateNotAvailable()
        val u = AppUpdater.create(context, source, PlayUpdateClient.create(fake), installed = InstalledVersion("1.0", 1))
        assertEquals(UpdateAction.None, runWithLooper { u.refresh() }.action)
        assertEquals(1, source.calls)
        runWithLooper { u.refresh() }
        assertEquals(1, source.calls)
        runWithLooper { u.check() }
        assertEquals(2, source.calls)
    }

    // ---- M4: Play 시간 제한 ----

    private class HangingPlay : PlayUpdateClient {
        override suspend fun availability(): PlayUpdateAvailability = kotlinx.coroutines.awaitCancellation()
        override suspend fun start(activity: Activity, type: PlayUpdateType): PlayUpdateResult = PlayUpdateResult.Canceled
        override fun installStates() = kotlinx.coroutines.flow.emptyFlow<PlayInstallState>()
        override suspend fun completeFlexibleUpdate(): PlayUpdateResult = PlayUpdateResult.Canceled
    }

    @Test
    fun `M4 Play 조회가 끝나지 않으면 시간 제한 뒤 Failed 로 접고 강제 판정은 차단으로 간다`() = kotlinx.coroutines.test.runTest {
        val u = AppUpdater.create(
            context, MinimumVersionSource { VersionRequirement(minimumVersionCode = 5) }, HangingPlay(),
            installed = InstalledVersion("1.0", 1),
        )
        val r = u.check()
        assertEquals(AppUpdater.DEFAULT_PLAY_TIMEOUT_MILLIS, testScheduler.currentTime)
        val failed = r.play as PlayUpdateAvailability.Failed
        assertTrue(failed.cause.message!!.contains("5000"))
        assertTrue(r.action is UpdateAction.BlockUntilStoreUpdate)
    }

    @Test
    fun `M4 시간 제한은 바꿀 수 있고 0 이하는 IllegalArgumentException`() = kotlinx.coroutines.test.runTest {
        val u = AppUpdater.create(context, null, HangingPlay(), installed = InstalledVersion("1.0", 1), playTimeoutMillis = 250)
        assertTrue(u.refresh().play is PlayUpdateAvailability.Failed)
        assertEquals(250L, testScheduler.currentTime)
        listOf(0L, -1L).forEach { bad ->
            val e = assertThrows(IllegalArgumentException::class.java) {
                AppUpdater.create(context, null, HangingPlay(), installed = InstalledVersion("1.0", 1), playTimeoutMillis = bad)
            }
            assertTrue(e.message!!.contains("$bad"))
        }
    }

    // ---- M5: Activity Result 런처 ----

    private val launches = mutableListOf<androidx.activity.result.IntentSenderRequest>()
    private val launcher = object : androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest>() {
        override fun launch(input: androidx.activity.result.IntentSenderRequest, options: androidx.core.app.ActivityOptionsCompat?) {
            launches += input
        }
        override fun unregister() = Unit
        override fun getContract(): androidx.activity.result.contract.ActivityResultContract<androidx.activity.result.IntentSenderRequest, *> =
            androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    }

    @Test
    fun `M5 런처 경로는 흐름을 띄우고 Launched 를 돌려준다`() {
        fake.setUpdateAvailable(11)
        val client = PlayUpdateClient.create(fake)
        assertEquals(PlayUpdateResult.Launched, runWithLooper { client.start(launcher, PlayUpdateType.IMMEDIATE) })
        assertTrue(fake.isImmediateFlowVisible)
        val u = AppUpdater.create(context, null, client, installed = InstalledVersion("1.0", 1))
        fake.setUpdateAvailable(11)
        assertEquals(PlayUpdateResult.Launched, runWithLooper { u.start(launcher, UpdateAction.StartPlay(PlayUpdateType.FLEXIBLE)) })
    }

    @Test
    fun `M5 런처 경로도 허용되지 않는 방식이면 Failed, 재개는 검사하지 않는다`() {
        fake.setUpdateAvailable(11, com.google.android.play.core.install.model.AppUpdateType.FLEXIBLE)
        val r = runWithLooper { PlayUpdateClient.create(fake).start(launcher, PlayUpdateType.IMMEDIATE) }
        assertTrue(r is PlayUpdateResult.Failed && r.cause.message!!.contains("IMMEDIATE"))

        val inProgress = inProgressInfo()
        var launched = false
        val client = PlayUpdateClient.create(object : AppUpdateManager by fake {
            override fun getAppUpdateInfo() = Tasks.forResult(inProgress)
            override fun startUpdateFlowForResult(
                info: AppUpdateInfo,
                l: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest>,
                options: AppUpdateOptions,
            ): Boolean { launched = true; return true }
        })
        val u = AppUpdater.create(context, null, client, installed = InstalledVersion("1.0", 1))
        assertEquals(PlayUpdateResult.Launched, runWithLooper { u.start(launcher, UpdateAction.ResumePlayImmediate) })
        assertTrue(launched)
    }

    @Test
    fun `M5 Play 가 흐름을 띄우지 않으면 Failed`() {
        fake.setUpdateAvailable(11)
        val client = PlayUpdateClient.create(object : AppUpdateManager by fake {
            override fun startUpdateFlowForResult(
                info: AppUpdateInfo,
                l: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest>,
                options: AppUpdateOptions,
            ): Boolean = false
        })
        val r = runWithLooper { client.start(launcher, PlayUpdateType.FLEXIBLE) }
        assertTrue(r is PlayUpdateResult.Failed && r.cause.message!!.contains("FLEXIBLE"))
    }

    @Test
    fun `M5 런처 경로의 설치와 Play 가 아닌 동작`() {
        fake.setUpdateAvailable(11)
        val u = AppUpdater.create(context, null, PlayUpdateClient.create(fake), installed = InstalledVersion("1.0", 1))
        runWithLooper { u.start(activity, UpdateAction.StartPlay(PlayUpdateType.FLEXIBLE)) }
        fake.userAcceptsUpdate(); fake.downloadStarts(); fake.downloadCompletes()
        assertEquals(PlayUpdateResult.Accepted, runWithLooper { u.start(launcher, UpdateAction.InstallDownloaded) })
        val required = ForceUpdateDecision.Required(InstalledVersion("1.0", 1), "2.0", null)
        listOf(UpdateAction.None, UpdateAction.BlockUntilStoreUpdate(required), UpdateAction.BlockUntilCheckSucceeds(offline)).forEach {
            val e = assertThrows(IllegalArgumentException::class.java) { runWithLooper { u.start(launcher, it) } }
            assertTrue(e.message!!.contains(it.toString()))
        }
    }

    @Test
    fun `M5 런처 경로를 구현하지 않은 클라이언트는 Failed`() {
        val r = runBlocking { HangingPlay().start(launcher, PlayUpdateType.FLEXIBLE) }
        assertTrue(r is PlayUpdateResult.Failed)
    }

    @Test
    fun `M5 런처 결과 코드 변환`() {
        assertEquals(PlayUpdateResult.Accepted, PlayUpdateResult.fromActivityResult(Activity.RESULT_OK))
        assertEquals(PlayUpdateResult.Canceled, PlayUpdateResult.fromActivityResult(Activity.RESULT_CANCELED))
        listOf(1, 2, -7, Int.MAX_VALUE).forEach {
            val r = PlayUpdateResult.fromActivityResult(it)
            assertTrue(r is PlayUpdateResult.Failed && r.cause.message!!.contains("$it"))
        }
    }

    private fun inProgressInfo(): AppUpdateInfo {
        val pi: android.app.PendingIntent? = null
        val zzb = AppUpdateInfo::class.java.methods.first { it.name == "zzb" && java.lang.reflect.Modifier.isStatic(it.modifiers) }
        return zzb.invoke(null, context.packageName, 77, 3, 0, null, 0, 0L, 0L, 0L, 0L, pi, pi, pi, pi, emptyMap<Any, Any>()) as AppUpdateInfo
    }

    // ---- L3: REQUIRES_UI_INTENT ----

    @Test
    fun `L3 REQUIRES_UI_INTENT 설치 상태를 따로 전달한다`() {
        val state = com.google.android.play.core.install.InstallState.zza(
            com.google.android.play.core.install.model.InstallStatus.REQUIRES_UI_INTENT, 0L, 0L, 0, context.packageName,
        )
        val client = PlayUpdateClient.create(object : AppUpdateManager by fake {
            override fun registerListener(listener: com.google.android.play.core.install.InstallStateUpdatedListener) = listener.onStateUpdate(state)
        })
        val first = runBlocking { client.installStates().first() }
        assertEquals(PlayInstallStatus.REQUIRES_UI_INTENT, first.status)
    }
}
