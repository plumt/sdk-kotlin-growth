package net.plumt.growth.review

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.android.play.core.review.testing.FakeReviewManager
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewPromptTest {

    private class MutableClock(var now: Long = 1_800_000_000_000L) : ReviewClock {
        override fun nowMillis(): Long = now
    }

    private class RecordingLauncher(var result: ReviewLaunchResult = ReviewLaunchResult.FlowFinished) : ReviewLauncher {
        var launches = 0
        override suspend fun launch(activity: Activity): ReviewLaunchResult {
            launches++
            return result
        }
    }

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val clock = MutableClock()
    private val store = InMemoryReviewStore()
    private val launcher = RecordingLauncher()
    private val policy = DefaultReviewPolicy(
        DefaultReviewPolicy.Config(minDaysSinceFirstLaunch = 1, minLaunchCount = 2, minSignificantEvents = 1),
    )
    private var version = "1.0.0"

    private val prompt = ReviewPrompt.create(
        context = ApplicationProvider.getApplicationContext<Application>(),
        policy = policy,
        store = store,
        launcher = launcher,
        clock = clock,
        appVersionProvider = { version },
    )

    private suspend fun makeEligible() {
        prompt.recordLaunch()
        prompt.recordLaunch()
        prompt.recordSignificantEvent()
        clock.now += 2.days.inWholeMilliseconds
    }

    @Test
    fun `조건 전에는 요청하지 않고 이유를 돌려준다`() = runBlocking {
        prompt.recordLaunch()
        val outcome = prompt.maybeRequest(activity)
        assertEquals(ReviewOutcome.NotEligible(ReviewDenyReason.TOO_SOON_AFTER_FIRST_LAUNCH), outcome)
        assertEquals(0, launcher.launches)
    }

    @Test
    fun `조건을 채우면 한 번 요청하고 이후에는 버전 한도로 막는다`() = runBlocking {
        makeEligible()
        assertEquals(ReviewOutcome.Requested, prompt.maybeRequest(activity))
        assertEquals(1, launcher.launches)
        val saved = store.load()
        assertEquals(clock.now, saved.lastRequestedAtMillis)
        assertEquals(1, saved.requestsFor("1.0.0"))

        clock.now += 365.days.inWholeMilliseconds
        assertEquals(ReviewOutcome.NotEligible(ReviewDenyReason.VERSION_LIMIT_REACHED), prompt.maybeRequest(activity))
        assertEquals(1, launcher.launches)

        version = "1.1.0"
        assertEquals(ReviewOutcome.Requested, prompt.maybeRequest(activity))
        assertEquals(2, launcher.launches)
    }

    @Test
    fun `Play 가 실패하면 Failed 이고 기록하지 않아 다시 시도할 수 있다`() = runBlocking {
        makeEligible()
        launcher.result = ReviewLaunchResult.Failed(ReviewException("실패"))
        val outcome = prompt.maybeRequest(activity)
        assertTrue(outcome is ReviewOutcome.Failed)
        assertEquals(null, store.load().lastRequestedAtMillis)

        launcher.result = ReviewLaunchResult.FlowFinished
        assertEquals(ReviewOutcome.Requested, prompt.maybeRequest(activity))
    }

    @Test
    fun `부정적 순간을 기록하면 쿨다운 동안 막는다`() = runBlocking {
        makeEligible()
        prompt.recordNegativeMoment()
        assertEquals(ReviewDecision.Deny(ReviewDenyReason.RECENT_NEGATIVE_MOMENT), prompt.evaluate())
        assertEquals(0, launcher.launches)
        clock.now += 15.days.inWholeMilliseconds
        assertEquals(ReviewDecision.Allow, prompt.evaluate())
    }

    @Test
    fun `거절을 기록하면 거절 쿨다운 동안 막는다`() = runBlocking {
        makeEligible()
        prompt.recordDeclined()
        assertEquals(ReviewDecision.Deny(ReviewDenyReason.RECENTLY_DECLINED), prompt.evaluate())
    }

    @Test
    fun `첫 실행 시각은 처음 한 번만 기록한다`() = runBlocking {
        prompt.recordLaunch()
        val first = store.load().firstLaunchAtMillis
        clock.now += 5.days.inWholeMilliseconds
        prompt.recordLaunch()
        assertEquals(first, store.load().firstLaunchAtMillis)
        assertEquals(2, store.load().launchCount)
    }

    @Test
    fun `저장소 읽기가 실패하면 원인을 담은 Failed 이다`() = runBlocking {
        val broken = object : ReviewStore {
            override suspend fun load(): ReviewState = throw IllegalStateException("디스크 오류")
            override suspend fun save(state: ReviewState) = Unit
        }
        val p = ReviewPrompt.create(
            ApplicationProvider.getApplicationContext<Application>(),
            policy, broken, launcher, clock, { version },
        )
        val outcome = p.maybeRequest(activity) as ReviewOutcome.Failed
        assertTrue(outcome.cause.cause is IllegalStateException)
        assertEquals(0, launcher.launches)
    }

    @Test
    fun `동시에 불러도 카운터가 유실되지 않는다`() = runBlocking {
        val jobs = List(50) { async { prompt.recordSignificantEvent() } }
        jobs.forEach { it.await() }
        assertEquals(50, store.load().significantEventCount)
    }

    @Test
    fun `Play FakeReviewManager 로 끝까지 요청하면 Requested 이다`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val real = ReviewPrompt.create(
            context, policy, store, PlayReviewLauncher.create(FakeReviewManager(context)), clock, { version },
        )
        val outcome = runWithLooper {
            makeEligible()
            real.maybeRequest(activity)
        }
        assertEquals(ReviewOutcome.Requested, outcome)
    }

    @Test
    fun `기본 SharedPreferences 저장소는 값을 보존한다`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val prefsStore = SharedPreferencesReviewStore(context, "review-test")
        val state = ReviewState(
            firstLaunchAtMillis = 10L, launchCount = 3, significantEventCount = 2, lastRequestedAtMillis = 20L,
            lastDeclinedAtMillis = 30L, lastNegativeAtMillis = 40L, versionOfRequests = "2.0", requestsInVersion = 1,
        )
        runBlocking {
            assertEquals(ReviewState(), prefsStore.load())
            prefsStore.save(state)
            assertEquals(state, SharedPreferencesReviewStore(context, "review-test").load())
            prefsStore.save(ReviewState())
            assertEquals(ReviewState(), prefsStore.load())
        }
    }

}
