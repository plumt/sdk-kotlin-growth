package net.plumt.growth.review

/**
 * 리뷰 모듈 안에서 끝내지 못한 작업(Play 요청 실패, 저장소 쓰기 실패)을 알린다. 원인은 [cause] 에 보존된다.
 *
 * 대부분 [ReviewOutcome.Failed] 로 돌아온다. 던져지는 것은 기록 함수·[ReviewPrompt.evaluate] 에서 저장소나 버전 공급자가 실패할 때다.
 *
 * Play 의 `com.google.android.play.core.review.ReviewException` 과 헷갈리지 않도록 이름을 달리했다.
 */
public class ReviewPromptException @JvmOverloads constructor(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
