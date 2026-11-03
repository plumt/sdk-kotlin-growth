package net.plumt.growth.review.internal

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.plumt.growth.review.ReviewClock
import net.plumt.growth.review.ReviewDecision
import net.plumt.growth.review.ReviewException
import net.plumt.growth.review.ReviewLaunchResult
import net.plumt.growth.review.ReviewLauncher
import net.plumt.growth.review.ReviewOutcome
import net.plumt.growth.review.ReviewPolicy
import net.plumt.growth.review.ReviewPrompt
import net.plumt.growth.review.ReviewState
import net.plumt.growth.review.ReviewStore

internal class DefaultReviewPrompt(
    private val policy: ReviewPolicy,
    private val store: ReviewStore,
    private val launcher: ReviewLauncher,
    private val clock: ReviewClock,
    private val appVersionProvider: () -> String,
) : ReviewPrompt {

    private val mutex = Mutex()

    override suspend fun recordLaunch() = update { state, now ->
        state.copy(
            firstLaunchAtMillis = state.firstLaunchAtMillis ?: now,
            launchCount = state.launchCount + 1,
        )
    }

    override suspend fun recordSignificantEvent() = update { state, _ ->
        state.copy(significantEventCount = state.significantEventCount + 1)
    }

    override suspend fun recordNegativeMoment() = update { state, now -> state.copy(lastNegativeAtMillis = now) }

    override suspend fun recordDeclined() = update { state, now -> state.copy(lastDeclinedAtMillis = now) }

    override suspend fun evaluate(): ReviewDecision = mutex.withLock {
        policy.evaluate(store.load(), clock.nowMillis(), appVersionProvider())
    }

    override suspend fun maybeRequest(activity: Activity): ReviewOutcome = mutex.withLock {
        val version = appVersionProvider()
        val now = clock.nowMillis()
        val state = try {
            store.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ReviewOutcome.Failed(e as? ReviewException ?: ReviewException("리뷰 카운터를 읽지 못했습니다.", e))
        }
        when (val decision = policy.evaluate(state, now, version)) {
            is ReviewDecision.Deny -> ReviewOutcome.NotEligible(decision.reason)
            ReviewDecision.Allow -> when (val result = launcher.launch(activity)) {
                is ReviewLaunchResult.Failed -> ReviewOutcome.Failed(result.cause)
                ReviewLaunchResult.FlowFinished -> record(state, version, now)
            }
        }
    }

    // 플로우가 끝난 뒤에만 기록한다. 기록이 실패하면 다음에 또 요청하게 되므로 실패로 알린다
    private suspend fun record(state: ReviewState, version: String, now: Long): ReviewOutcome = try {
        store.save(
            state.copy(
                lastRequestedAtMillis = now,
                versionOfRequests = version,
                requestsInVersion = state.requestsFor(version) + 1,
            ),
        )
        ReviewOutcome.Requested
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ReviewOutcome.Failed(e as? ReviewException ?: ReviewException("리뷰 요청 기록을 저장하지 못했습니다.", e))
    }

    private suspend fun update(transform: (ReviewState, Long) -> ReviewState) {
        mutex.withLock {
            val now = clock.nowMillis()
            store.save(transform(store.load(), now))
        }
    }
}

internal fun installedVersionName(context: Context): String {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return info.versionName
        ?: throw ReviewException("설치된 패키지에 versionName 이 없습니다: ${context.packageName}")
}
