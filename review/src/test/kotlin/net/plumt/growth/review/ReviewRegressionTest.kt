package net.plumt.growth.review

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 리뷰 보고서의 결함을 재현하는 회귀 테스트. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewRegressionTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val allowAll = ReviewPolicy { _, _, _ -> ReviewDecision.Allow }

    private class GateLauncher : ReviewLauncher {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<ReviewLaunchResult>()
        override suspend fun launch(activity: Activity): ReviewLaunchResult {
            entered.complete(Unit)
            return release.await()
        }
    }

    @Test
    fun `M1 리뷰 흐름이 끝나지 않아도 기록 함수는 멈추지 않는다`() = runBlocking {
        val store = InMemoryReviewStore()
        val launcher = GateLauncher()
        val prompt = ReviewPrompt.create(context, allowAll, store, launcher, ReviewClock { 1000L }) { "1.0" }
        val request = async(start = CoroutineStart.UNDISPATCHED) { prompt.maybeRequest(activity) }
        launcher.entered.await()
        withTimeout(2_000) { prompt.recordLaunch() }
        launcher.release.complete(ReviewLaunchResult.FlowFinished)
        assertEquals(ReviewOutcome.Requested, request.await())
        val saved = store.load()
        assertEquals("흐름 중 기록한 실행이 요청 기록에 덮이지 않는다", 1, saved.launchCount)
        assertEquals(1, saved.requestsFor("1.0"))
    }

    @Test
    fun `L5 버전 공급자가 던지면 maybeRequest 는 Failed 로 알린다`() = runBlocking {
        val boom = IllegalStateException("버전 없음")
        val prompt = ReviewPrompt.create(context, allowAll, InMemoryReviewStore(), GateLauncher(), ReviewClock { 0L }) { throw boom }
        val outcome = prompt.maybeRequest(activity)
        assertTrue("받은 값: $outcome", outcome is ReviewOutcome.Failed && outcome.cause.cause === boom)
    }

    @Test
    fun `M1 흐름이 떠 있는 동안 두 번째 요청은 기다리지 않고 REQUEST_IN_PROGRESS`() = runBlocking {
        val launcher = GateLauncher()
        val prompt = ReviewPrompt.create(context, allowAll, InMemoryReviewStore(), launcher, ReviewClock { 1L }) { "1.0" }
        val first = async(start = CoroutineStart.UNDISPATCHED) { prompt.maybeRequest(activity) }
        launcher.entered.await()
        assertEquals(ReviewOutcome.NotEligible(ReviewDenyReason.REQUEST_IN_PROGRESS), withTimeout(2_000) { prompt.maybeRequest(activity) })
        assertEquals(ReviewDecision.Allow, withTimeout(2_000) { prompt.evaluate() })
        launcher.release.complete(ReviewLaunchResult.FlowFinished)
        assertEquals(ReviewOutcome.Requested, first.await())
        // 끝난 뒤에는 다시 요청할 수 있다(정책이 허락하면)
        assertEquals(ReviewOutcome.Requested, prompt.maybeRequest(activity))
    }

    @Test
    fun `M1 흐름이 실패하거나 취소돼도 진행 중 표시가 풀린다`() = runBlocking {
        val failing = object : ReviewLauncher {
            var calls = 0
            override suspend fun launch(activity: Activity): ReviewLaunchResult {
                calls++
                if (calls == 1) throw kotlinx.coroutines.CancellationException("화면 종료")
                return ReviewLaunchResult.Failed(ReviewPromptException("Play 없음"))
            }
        }
        val prompt = ReviewPrompt.create(context, allowAll, InMemoryReviewStore(), failing, ReviewClock { 1L }) { "1.0" }
        org.junit.Assert.assertThrows(kotlinx.coroutines.CancellationException::class.java) { runBlocking { prompt.maybeRequest(activity) } }
        assertTrue(prompt.maybeRequest(activity) is ReviewOutcome.Failed)
        assertTrue("실패 뒤에도 다시 시도한다", prompt.maybeRequest(activity) is ReviewOutcome.Failed)
        assertEquals(3, failing.calls)
    }

    @Test
    fun `M1 흐름 뒤 기록 저장이 실패하면 Failed 이고 원인을 보존한다`() = runBlocking {
        val boom = java.io.IOException("디스크 가득")
        var loads = 0
        val store = object : ReviewStore {
            override suspend fun load(): ReviewState { loads++; return ReviewState() }
            override suspend fun save(state: ReviewState) = throw boom
        }
        val launcher = GateLauncher().apply { release.complete(ReviewLaunchResult.FlowFinished) }
        val prompt = ReviewPrompt.create(context, allowAll, store, launcher, ReviewClock { 1L }) { "1.0" }
        val outcome = prompt.maybeRequest(activity) as ReviewOutcome.Failed
        assertTrue(outcome.cause.cause === boom)
        assertEquals("판정 전 한 번, 기록 전 한 번 다시 읽는다", 2, loads)
    }

    @Test
    fun `M6 reset 은 카운터와 첫 실행 기록을 지운다`() = runBlocking {
        val store = InMemoryReviewStore(ReviewState(firstLaunchAtMillis = 5, launchCount = 9, versionOfRequests = "1.0", requestsInVersion = 1))
        val prompt = ReviewPrompt.create(context, DefaultReviewPolicy(), store, GateLauncher(), ReviewClock { 10L }) { "1.0" }
        prompt.reset()
        assertEquals(ReviewState(), store.load())
        assertEquals(ReviewDecision.Deny(ReviewDenyReason.NO_LAUNCH_RECORDED), prompt.evaluate())
    }

    @Test
    fun `M6 reset 은 SharedPreferences 저장소에서도 프로세스 재시작 뒤 비어 있다`() = runBlocking {
        val name = "reset-test"
        val prompt = ReviewPrompt.create(context, allowAll, SharedPreferencesReviewStore(context, name), GateLauncher(), ReviewClock { 3L }) { "1.0" }
        prompt.recordLaunch()
        prompt.recordNegativeMoment()
        prompt.reset()
        assertEquals(ReviewState(), SharedPreferencesReviewStore(context, name).load())
    }

    @Test
    fun `L5 버전 공급자가 SDK 예외를 던지면 감싸지 않고 그대로 담는다`() = runBlocking {
        val own = ReviewPromptException("versionName 없음")
        val prompt = ReviewPrompt.create(context, allowAll, InMemoryReviewStore(), GateLauncher(), ReviewClock { 0L }) { throw own }
        assertTrue((prompt.maybeRequest(activity) as ReviewOutcome.Failed).cause === own)
    }
}
