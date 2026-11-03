package net.plumt.growth.review

/**
 * 리뷰 요청 판정에 쓰이는 카운터와 시각의 스냅샷. 저장은 [ReviewStore] 가 한다.
 *
 * 시각은 모두 epoch millis 이고 `null` 은 "아직 한 번도 없었다" 이다.
 */
public data class ReviewState(
    /** 처음 [ReviewPrompt.recordLaunch] 가 불린 시각. 설치 후 경과 일수의 기준이다. */
    val firstLaunchAtMillis: Long? = null,
    /** 누적 실행 횟수. */
    val launchCount: Int = 0,
    /** 누적 의미 있는 사건 수([ReviewPrompt.recordSignificantEvent]). */
    val significantEventCount: Int = 0,
    /**
     * 마지막으로 Play 리뷰 플로우를 끝까지 **요청한** 시각.
     * Play 는 다이얼로그를 실제로 띄웠는지 알려주지 않으므로 "표시됐다" 가 아니라 "요청했다" 이다.
     */
    val lastRequestedAtMillis: Long? = null,
    /** 앱이 사용자의 거절을 알려준([ReviewPrompt.recordDeclined]) 마지막 시각. */
    val lastDeclinedAtMillis: Long? = null,
    /** 앱이 부정적 순간을 알려준([ReviewPrompt.recordNegativeMoment]) 마지막 시각. */
    val lastNegativeAtMillis: Long? = null,
    /** 요청 횟수를 세고 있는 앱 버전. 버전이 바뀌면 [requestsInVersion] 은 0 부터 다시 센다. */
    val versionOfRequests: String? = null,
    /** [versionOfRequests] 에서 요청한 횟수. */
    val requestsInVersion: Int = 0,
) {
    init {
        require(launchCount >= 0) { "launchCount 는 0 이상이어야 합니다. 받은 값: $launchCount" }
        require(significantEventCount >= 0) { "significantEventCount 는 0 이상이어야 합니다. 받은 값: $significantEventCount" }
        require(requestsInVersion >= 0) { "requestsInVersion 은 0 이상이어야 합니다. 받은 값: $requestsInVersion" }
    }

    /** [appVersion] 에서 이미 요청한 횟수. 다른 버전의 기록이면 0. */
    public fun requestsFor(appVersion: String): Int =
        if (versionOfRequests == appVersion) requestsInVersion else 0
}
