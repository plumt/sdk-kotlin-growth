package net.plumt.growth.review

import android.app.Activity
import android.content.Context
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Google Play In-App Review 로 [ReviewLauncher] 를 구현한다.
 *
 * Play 의 쿼터 때문에 요청해도 다이얼로그가 뜨지 않을 수 있고, 앱은 그것을 알 수 없다.
 * 그래서 성공 결과도 [ReviewLaunchResult.FlowFinished] 이며 "표시됨" 이라고 말하지 않는다.
 * 테스트에서는 Play 의 `FakeReviewManager` 를 [create] 에 넘겨 쓴다.
 */
public class PlayReviewLauncher internal constructor(
    private val reviewManager: ReviewManager,
) : ReviewLauncher {

    override suspend fun launch(activity: Activity): ReviewLaunchResult = try {
        val info = requestInfo().getOrElse { return ReviewLaunchResult.Failed(it.asReviewException("리뷰 정보를 가져오지 못했습니다.")) }
        launchFlow(activity, info).onFailure {
            return ReviewLaunchResult.Failed(it.asReviewException("리뷰 플로우를 시작하지 못했습니다."))
        }
        ReviewLaunchResult.FlowFinished
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Task 를 만들기 전에 던지는 경우(종료 중인 Activity 등)
        ReviewLaunchResult.Failed(e.asReviewException("Play 리뷰 플로우가 실패했습니다."))
    }

    // 실패를 예외로 재개하면 코루틴의 스택 트레이스 복구가 예외를 복제해 cause 가 바뀐다 — Result 값으로 넘긴다
    private suspend fun requestInfo(): Result<ReviewInfo> = suspendCancellableCoroutine { cont ->
        reviewManager.requestReviewFlow().addOnCompleteListener { task -> cont.resume(task.toResult()) }
    }

    private suspend fun launchFlow(activity: Activity, info: ReviewInfo): Result<Unit> = suspendCancellableCoroutine { cont ->
        reviewManager.launchReviewFlow(activity, info).addOnCompleteListener { task ->
            cont.resume(task.toResult().map { })
        }
    }

    private fun <T> Task<T>.toResult(): Result<T> =
        if (isSuccessful) Result.success(result) else Result.failure(exception ?: IllegalStateException("Task 가 원인 없이 실패했습니다."))

    private fun Throwable.asReviewException(message: String): ReviewException = ReviewException(message, this)

    public companion object {
        /** Play 가 제공하는 실제 [ReviewManager] 로 만든다. */
        @JvmStatic
        public fun create(context: Context): PlayReviewLauncher =
            PlayReviewLauncher(ReviewManagerFactory.create(context.applicationContext))

        /**
         * 지정한 [ReviewManager] 로 만든다 — 테스트에서 `FakeReviewManager` 를 끼울 때 쓴다.
         * 이 시그니처는 Play 타입을 드러내므로 일반 앱 코드에서는 [create] 를 쓴다.
         */
        @JvmStatic
        public fun create(reviewManager: ReviewManager): PlayReviewLauncher = PlayReviewLauncher(reviewManager)
    }
}
