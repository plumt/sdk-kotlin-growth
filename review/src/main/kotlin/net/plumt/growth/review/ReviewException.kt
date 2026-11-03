package net.plumt.growth.review

/**
 * 리뷰 모듈 안에서 끝내지 못한 작업(Play 요청 실패, 저장소 쓰기 실패)을 알린다. 원인은 [cause] 에 보존된다.
 *
 * [ReviewOutcome.Failed] 로 돌아오며, 던져지는 것은 저장소 구현이 직접 던지는 경우뿐이다.
 */
public class ReviewException @JvmOverloads constructor(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
