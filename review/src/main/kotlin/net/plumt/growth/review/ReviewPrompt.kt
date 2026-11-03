package net.plumt.growth.review

import android.app.Activity
import android.content.Context
import net.plumt.growth.review.internal.DefaultReviewPrompt
import net.plumt.growth.review.internal.installedVersionName

/**
 * 인앱 리뷰 요청의 진입점. 카운터를 쌓고, 정책이 허락할 때만 Play 리뷰 플로우를 요청한다.
 *
 * ```kotlin
 * val review = ReviewPrompt.create(context)        // 앱에 하나만 두고 공유한다
 * review.recordLaunch()                            // 앱을 켤 때마다
 * review.recordSignificantEvent()                  // 사용자가 핵심 가치를 얻은 순간마다
 * review.maybeRequest(activity)                    // 요청해도 좋은 자연스러운 지점에서
 * ```
 *
 * 스레드: 모든 함수는 `suspend` 이고 내부에서 직렬화된다(Mutex). [maybeRequest] 의 Play 호출은 메인 스레드에서 해도 된다.
 * 한 프로세스 안에서 [ReviewPrompt] 를 여럿 만들어 같은 저장소를 쓰면 직렬화가 보장되지 않는다.
 */
public interface ReviewPrompt {

    /** 앱 실행을 기록한다. 처음 부르면 첫 실행 시각도 남긴다. */
    public suspend fun recordLaunch()

    /** 사용자가 앱의 핵심 가치를 얻은 사건(작업 완료·결과 확인 등) 하나를 기록한다. */
    public suspend fun recordSignificantEvent()

    /**
     * 크래시·오류·결제 실패 등 사용자가 불쾌했을 순간을 기록한다. 정책에 따라 한동안 요청을 막는다.
     * 크래시는 다음 실행에서 이전 비정상 종료를 감지했을 때 부르는 것이 일반적이다.
     */
    public suspend fun recordNegativeMoment()

    /**
     * 사용자가 리뷰를 거절했음을 기록한다. 앱이 먼저 "앱이 마음에 드시나요?" 같은 자체 질문을 띄우고
     * 사용자가 거절한 경우에 부른다. Play 다이얼로그의 거절은 앱이 알 수 없다.
     */
    public suspend fun recordDeclined()

    /** 요청하지 않고 정책 판정만 본다. 요청 버튼 노출 여부 등에 쓴다. */
    public suspend fun evaluate(): ReviewDecision

    /**
     * 정책이 허락하면 Play 리뷰 플로우를 요청하고, 요청을 시도한 사실을 기록한다.
     *
     * 반환은 [ReviewOutcome] 이다. [ReviewOutcome.Requested] 는 "플로우가 끝났다" 일 뿐 다이얼로그가 보였다는 뜻이 아니다.
     * 플로우가 실패하면 기록하지 않으므로 다음 호출에서 다시 시도한다.
     */
    public suspend fun maybeRequest(activity: Activity): ReviewOutcome

    public companion object {
        /**
         * 기본 구성으로 만든다 — [DefaultReviewPolicy], [SharedPreferencesReviewStore], [PlayReviewLauncher], 시스템 시각.
         * 각 요소를 인자로 바꿔 끼울 수 있다.
         *
         * @param appVersionProvider 앱의 현재 버전 이름. 기본은 설치된 패키지의 `versionName` 이다
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            context: Context,
            policy: ReviewPolicy = DefaultReviewPolicy(),
            store: ReviewStore = SharedPreferencesReviewStore(context),
            launcher: ReviewLauncher = PlayReviewLauncher.create(context),
            clock: ReviewClock = ReviewClock.System,
            appVersionProvider: () -> String = { installedVersionName(context) },
        ): ReviewPrompt = DefaultReviewPrompt(policy, store, launcher, clock, appVersionProvider)
    }
}

/** [ReviewPrompt.maybeRequest] 의 결과. */
public sealed interface ReviewOutcome {

    /** 정책이 허락하지 않아 요청하지 않았다. */
    public data class NotEligible(val reason: ReviewDenyReason) : ReviewOutcome

    /**
     * Play 리뷰 플로우가 끝났다. **다이얼로그가 보였는지는 알 수 없다** — Play 가 쿼터로 건너뛰었을 수 있다.
     */
    public data object Requested : ReviewOutcome

    /** 요청을 끝내지 못했다. 카운터는 바뀌지 않았다. */
    public data class Failed(val cause: ReviewException) : ReviewOutcome
}
