package net.plumt.growth.update

/**
 * 업데이트 모듈 안에서 끝내지 못한 작업(출처 조회 실패, Play 요청 실패)을 알린다. 원인은 [cause] 에 보존된다.
 *
 * 대부분 [ForceUpdateDecision.CheckFailed]·[PlayUpdateAvailability.Failed]·[PlayUpdateResult.Failed] 의 값으로 돌아오며,
 * [MinimumVersionSource] 구현이 직접 던져도 된다(어떤 예외든 [ForceUpdateChecker] 가 감싼다).
 */
public class UpdateException @JvmOverloads constructor(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
