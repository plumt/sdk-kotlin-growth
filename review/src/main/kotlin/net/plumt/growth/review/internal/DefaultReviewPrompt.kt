package net.plumt.growth.review.internal

import android.app.Activity
import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.plumt.growth.review.ReviewClock
import net.plumt.growth.review.ReviewDecision
import net.plumt.growth.review.ReviewDenyReason
import net.plumt.growth.review.ReviewPromptException
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

    // 저장소 읽기·쓰기 구간만 감싼다. Play 흐름은 잠금 밖에서 기다린다 — 흐름이 오래 걸려도 기록 함수가 멈추지 않는다
    private val mutex = Mutex()

    // Play 흐름이 떠 있는 동안 두 번째 요청을 막는다
    private val requesting = AtomicBoolean(false)

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

    override suspend fun reset() {
        mutex.withLock { store.save(ReviewState()) }
    }

    override suspend fun evaluate(): ReviewDecision = mutex.withLock {
        policy.evaluate(store.load(), clock.nowMillis(), appVersionProvider())
    }

    override suspend fun maybeRequest(activity: Activity): ReviewOutcome {
        if (!requesting.compareAndSet(false, true)) return ReviewOutcome.NotEligible(ReviewDenyReason.REQUEST_IN_PROGRESS)
        try {
            val version = guard("앱 버전을 읽지 못했습니다.") { appVersionProvider() }.getOrElse { return it.failed() }
            val now = clock.nowMillis()
            val state = guard("리뷰 카운터를 읽지 못했습니다.") { mutex.withLock { store.load() } }.getOrElse { return it.failed() }
            val decision = policy.evaluate(state, now, version)
            if (decision is ReviewDecision.Deny) return ReviewOutcome.NotEligible(decision.reason)
            return when (val result = launcher.launch(activity)) {
                is ReviewLaunchResult.Failed -> ReviewOutcome.Failed(result.cause)
                ReviewLaunchResult.FlowFinished -> record(version, now)
            }
        } finally {
            requesting.set(false)
        }
    }

    // 플로우가 끝난 뒤에만 기록한다. 흐름 중에 들어온 기록을 덮지 않도록 다시 읽고 고친다.
    // 기록이 실패하면 다음에 또 요청하게 되므로 실패로 알린다
    private suspend fun record(version: String, now: Long): ReviewOutcome =
        guard("리뷰 요청 기록을 저장하지 못했습니다.") {
            mutex.withLock {
                val latest = store.load()
                store.save(
                    latest.copy(
                        lastRequestedAtMillis = now,
                        versionOfRequests = version,
                        requestsInVersion = latest.requestsFor(version) + 1,
                    ),
                )
            }
        }.fold({ ReviewOutcome.Requested }, { it.failed() })

    // 취소는 그대로 전파하고 그 밖의 실패는 원인을 보존한 SDK 예외로 접는다
    private inline fun <T> guard(message: String, block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e as? ReviewPromptException ?: ReviewPromptException(message, e))
    }

    private fun Throwable.failed(): ReviewOutcome = ReviewOutcome.Failed(this as ReviewPromptException)

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
        ?: throw ReviewPromptException("설치된 패키지에 versionName 이 없습니다: ${context.packageName}")
}
