package net.plumt.growth.review

import android.app.Activity
import android.app.Application
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import kotlin.random.Random
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewPromptScenarioTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private class Clock(var now: Long = 1_800_000_000_000L) : ReviewClock { override fun nowMillis() = now }
    private class Launcher : ReviewLauncher {
        var count = 0
        override suspend fun launch(activity: Activity): ReviewLaunchResult { count++; return ReviewLaunchResult.FlowFinished }
    }

    @Test
    fun `프로세스 재시작 후에도 카운터가 이어져 한도를 지킨다`() = runBlocking<Unit> {
        val clock = Clock()
        val launcher = Launcher()
        val config = DefaultReviewPolicy.Config(minDaysSinceFirstLaunch = 0, minLaunchCount = 2, minSignificantEvents = 0)
        fun build() = ReviewPrompt.create(context, DefaultReviewPolicy(config), SharedPreferencesReviewStore(context, "scenario"), launcher, clock) { "1.0" }

        var p = build()
        p.recordLaunch()
        assertEquals(ReviewOutcome.NotEligible(ReviewDenyReason.NOT_ENOUGH_LAUNCHES), p.maybeRequest(activity))
        p = build() // 재시작
        p.recordLaunch()
        assertEquals(ReviewOutcome.Requested, p.maybeRequest(activity))
        p = build() // 재시작
        assertEquals(ReviewOutcome.NotEligible(ReviewDenyReason.RECENTLY_REQUESTED), p.maybeRequest(activity))
        assertEquals(1, launcher.count)
    }

    @Test
    fun `정책 시간 경계 - 쿨다운 직전 직후를 주입 시계로 확인한다`() = runBlocking<Unit> {
        val clock = Clock()
        val launcher = Launcher()
        val store = InMemoryReviewStore()
        val config = DefaultReviewPolicy.Config(
            minDaysSinceFirstLaunch = 2, minLaunchCount = 1, minSignificantEvents = 0,
            cooldownAfterRequest = 10.days, maxRequestsPerVersion = 99,
        )
        var version = "1"
        val p = ReviewPrompt.create(context, DefaultReviewPolicy(config), store, launcher, clock) { version }
        p.recordLaunch()
        clock.now += 2.days.inWholeMilliseconds - 1
        assertEquals(ReviewDecision.Deny(ReviewDenyReason.TOO_SOON_AFTER_FIRST_LAUNCH), p.evaluate())
        clock.now += 1
        assertEquals(ReviewOutcome.Requested, p.maybeRequest(activity))
        clock.now += 10.days.inWholeMilliseconds - 1
        assertEquals(ReviewDecision.Deny(ReviewDenyReason.RECENTLY_REQUESTED), p.evaluate())
        clock.now += 1
        assertEquals(ReviewDecision.Allow, p.evaluate())
        version = "2"
        assertEquals(1, store.load().requestsFor("1"))
    }

    @Test
    fun `저장소 쓰기가 실패하면 Failed 이고 원인을 보존한다`() = runBlocking<Unit> {
        val boom = IllegalStateException("디스크")
        val store = object : ReviewStore {
            val inner = InMemoryReviewStore()
            var failSave = false
            override suspend fun load() = inner.load()
            override suspend fun save(state: ReviewState) { if (failSave) throw boom else inner.save(state) }
        }
        val config = DefaultReviewPolicy.Config(minDaysSinceFirstLaunch = 0, minLaunchCount = 1, minSignificantEvents = 0)
        val p = ReviewPrompt.create(context, DefaultReviewPolicy(config), store, Launcher(), Clock()) { "1" }
        p.recordLaunch()
        store.failSave = true
        val out = p.maybeRequest(activity) as ReviewOutcome.Failed
        assertEquals(boom, out.cause.cause)
        // record* 는 저장소 예외를 그대로 던진다
        assertThrows(IllegalStateException::class.java) { runBlocking { p.recordLaunch() } }
    }

    @Test
    fun `ReviewException 을 던지는 저장소는 감싸지 않는다`() = runBlocking<Unit> {
        val own = ReviewException("직접")
        val store = object : ReviewStore {
            override suspend fun load(): ReviewState = throw own
            override suspend fun save(state: ReviewState) = Unit
        }
        val p = ReviewPrompt.create(context, DefaultReviewPolicy(), store, Launcher(), Clock()) { "1" }
        assertEquals(own, (p.maybeRequest(activity) as ReviewOutcome.Failed).cause)
    }

    @Test
    fun `저장소 읽기 중 취소는 Failed 로 접지 않는다`() {
        val store = object : ReviewStore {
            override suspend fun load(): ReviewState = throw CancellationException("취소")
            override suspend fun save(state: ReviewState) = Unit
        }
        val p = ReviewPrompt.create(context, DefaultReviewPolicy(), store, Launcher(), Clock()) { "1" }
        assertThrows(CancellationException::class.java) { runBlocking { p.maybeRequest(activity) } }
    }

    @Test
    fun `여러 스레드에서 동시에 기록해도 유실이 없다`() = runBlocking<Unit> {
        val store = InMemoryReviewStore()
        val p = ReviewPrompt.create(context, DefaultReviewPolicy(), store, Launcher(), Clock()) { "1" }
        List(200) { i ->
            async(Dispatchers.Default) { if (i % 2 == 0) p.recordLaunch() else p.recordSignificantEvent() }
        }.awaitAll()
        assertEquals(100, store.load().launchCount)
        assertEquals(100, store.load().significantEventCount)
    }

    @Test
    fun `동시 요청은 한 번만 Play 를 부른다`() = runBlocking<Unit> {
        val launcher = Launcher()
        val config = DefaultReviewPolicy.Config(minDaysSinceFirstLaunch = 0, minLaunchCount = 1, minSignificantEvents = 0)
        val p = ReviewPrompt.create(context, DefaultReviewPolicy(config), InMemoryReviewStore(), launcher, Clock()) { "1" }
        p.recordLaunch()
        val outcomes = List(20) { async(Dispatchers.Default) { p.maybeRequest(activity) } }.awaitAll()
        assertEquals(1, outcomes.count { it == ReviewOutcome.Requested })
        assertEquals(1, launcher.count)
    }

    @Test
    fun `기본 앱 버전 공급자는 설치된 패키지의 versionName 을 쓴다`() = runBlocking<Unit> {
        val pm = shadowOf(context.packageManager)
        val info: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName = "7.7.7"
        pm.installPackage(info)
        val store = InMemoryReviewStore()
        val config = DefaultReviewPolicy.Config(minDaysSinceFirstLaunch = 0, minLaunchCount = 1, minSignificantEvents = 0)
        val p = ReviewPrompt.create(context = context, policy = DefaultReviewPolicy(config), store = store, launcher = Launcher(), clock = Clock())
        p.recordLaunch()
        assertEquals(ReviewOutcome.Requested, p.maybeRequest(activity))
        assertEquals("7.7.7", store.load().versionOfRequests)
    }

    @Test
    fun `versionName 이 없으면 ReviewException`() {
        val info: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName = null
        shadowOf(context.packageManager).installPackage(info)
        val p = ReviewPrompt.create(context = context, store = InMemoryReviewStore(), launcher = Launcher(), clock = Clock())
        val e = assertThrows(ReviewException::class.java) { runBlocking { p.evaluate() } }
        assertTrue(e.message!!.contains("versionName"))
    }

    @Test
    fun `무작위 연산열에서도 예외가 없고 정책 불변식을 지킨다 - 시드 고정`() = runBlocking<Unit> {
        val rnd = Random(20260101)
        repeat(30) { run ->
            val clock = Clock()
            val launcher = Launcher()
            val store = InMemoryReviewStore()
            val config = DefaultReviewPolicy.Config(
                minDaysSinceFirstLaunch = rnd.nextInt(0, 4), minLaunchCount = rnd.nextInt(0, 4),
                minSignificantEvents = rnd.nextInt(0, 4), cooldownAfterRequest = rnd.nextInt(0, 20).days,
                cooldownAfterDeclined = rnd.nextInt(0, 20).days, cooldownAfterNegative = rnd.nextInt(0, 20).days,
                maxRequestsPerVersion = rnd.nextInt(1, 3),
            )
            var version = "1.0"
            val p = ReviewPrompt.create(context, DefaultReviewPolicy(config), store, launcher, clock) { version }
            repeat(200) {
                when (rnd.nextInt(8)) {
                    0 -> p.recordLaunch()
                    1 -> p.recordSignificantEvent()
                    2 -> p.recordNegativeMoment()
                    3 -> p.recordDeclined()
                    4 -> clock.now += rnd.nextLong(0, 5L * 24 * 3600 * 1000)
                    5 -> version = "1.${rnd.nextInt(0, 3)}"
                    else -> {
                        val before = launcher.count
                        val state = store.load()
                        val expectAllow = DefaultReviewPolicy(config).evaluate(state, clock.now, version) == ReviewDecision.Allow
                        val out = p.maybeRequest(activity)
                        assertEquals("run=$run", expectAllow, out == ReviewOutcome.Requested)
                        assertEquals(if (expectAllow) before + 1 else before, launcher.count)
                        if (expectAllow) {
                            // 불변식: 요청 직후에는 같은 버전 한도·요청 쿨다운에 걸린다(쿨다운이 0 이어도 한도는 유지)
                            val after = store.load()
                            assertTrue(after.requestsFor(version) >= 1)
                            assertEquals(clock.now, after.lastRequestedAtMillis)
                        }
                    }
                }
            }
        }
    }
}
