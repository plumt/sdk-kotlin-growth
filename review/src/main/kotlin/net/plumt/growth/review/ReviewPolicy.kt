package net.plumt.growth.review

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * 지금 리뷰를 요청해도 되는지 판정하는 정책. 교체하려면 이 인터페이스를 구현해 [ReviewPrompt.create] 에 넘긴다.
 *
 * 구현은 부수효과가 없는 순수 함수여야 한다(시각은 인자로만 받는다).
 */
public fun interface ReviewPolicy {

    /**
     * @param state 현재 카운터
     * @param nowMillis 현재 시각
     * @param appVersion 앱의 현재 버전 이름
     */
    public fun evaluate(state: ReviewState, nowMillis: Long, appVersion: String): ReviewDecision
}

/** [ReviewPolicy] 의 판정 결과. */
public sealed interface ReviewDecision {

    /** 요청해도 된다. */
    public data object Allow : ReviewDecision

    /** 요청하지 않는다. [reason] 은 첫 번째로 걸린 이유다. */
    public data class Deny(val reason: ReviewDenyReason) : ReviewDecision
}

/** [DefaultReviewPolicy] 가 요청을 막는 이유. 검사 순서대로 나열한다. */
public enum class ReviewDenyReason {
    /** 첫 실행 기록이 없다([ReviewPrompt.recordLaunch] 가 아직 불리지 않았다). */
    NO_LAUNCH_RECORDED,

    /** 첫 실행 후 [DefaultReviewPolicy.Config.minDaysSinceFirstLaunch] 일이 지나지 않았다. */
    TOO_SOON_AFTER_FIRST_LAUNCH,

    /** 실행 횟수가 [DefaultReviewPolicy.Config.minLaunchCount] 미만이다. */
    NOT_ENOUGH_LAUNCHES,

    /** 의미 있는 사건 수가 [DefaultReviewPolicy.Config.minSignificantEvents] 미만이다. */
    NOT_ENOUGH_EVENTS,

    /** 부정적 순간 이후 [DefaultReviewPolicy.Config.cooldownAfterNegative] 가 지나지 않았다. */
    RECENT_NEGATIVE_MOMENT,

    /** 사용자가 거절한 뒤 [DefaultReviewPolicy.Config.cooldownAfterDeclined] 가 지나지 않았다. */
    RECENTLY_DECLINED,

    /** 마지막 요청 후 [DefaultReviewPolicy.Config.cooldownAfterRequest] 가 지나지 않았다. */
    RECENTLY_REQUESTED,

    /** 이 앱 버전에서 [DefaultReviewPolicy.Config.maxRequestsPerVersion] 번 요청했다. */
    VERSION_LIMIT_REACHED,

    /** 정책이 아니라 [ReviewPrompt] 가 붙이는 이유 — 같은 [ReviewPrompt] 의 다른 [ReviewPrompt.maybeRequest] 가 Play 흐름을 띄워 둔 상태다. */
    REQUEST_IN_PROGRESS,
}

/**
 * 설정으로 조정하는 기본 정책. 모든 조건을 만족해야 [ReviewDecision.Allow] 이다.
 *
 * 시계가 되감겨 "마지막 사건이 미래" 로 보이면 경과 시간이 음수가 되어 쿨다운이 걸린 것으로 판정한다(안전한 쪽).
 */
public class DefaultReviewPolicy @JvmOverloads constructor(
    private val config: Config = Config(),
) : ReviewPolicy {

    /**
     * 기본 정책의 임계값. 값이 범위를 벗어나면 생성 시 [IllegalArgumentException] 이다.
     *
     * @property minDaysSinceFirstLaunch 첫 실행 후 최소 경과 일수
     * @property minLaunchCount 최소 실행 횟수
     * @property minSignificantEvents 최소 의미 있는 사건 수
     * @property cooldownAfterRequest 요청 후 다음 요청까지 최소 간격
     * @property cooldownAfterDeclined 사용자 거절 후 최소 간격
     * @property cooldownAfterNegative 크래시·오류 등 부정적 순간 후 최소 간격
     * @property maxRequestsPerVersion 앱 버전 하나당 최대 요청 수
     */
    public data class Config @JvmOverloads constructor(
        val minDaysSinceFirstLaunch: Int = 3,
        val minLaunchCount: Int = 5,
        val minSignificantEvents: Int = 3,
        val cooldownAfterRequest: Duration = 60.days,
        val cooldownAfterDeclined: Duration = 120.days,
        val cooldownAfterNegative: Duration = 14.days,
        val maxRequestsPerVersion: Int = 1,
    ) {
        init {
            require(minDaysSinceFirstLaunch >= 0) { "minDaysSinceFirstLaunch 는 0 이상이어야 합니다. 받은 값: $minDaysSinceFirstLaunch" }
            require(minLaunchCount >= 0) { "minLaunchCount 는 0 이상이어야 합니다. 받은 값: $minLaunchCount" }
            require(minSignificantEvents >= 0) { "minSignificantEvents 는 0 이상이어야 합니다. 받은 값: $minSignificantEvents" }
            require(cooldownAfterRequest >= Duration.ZERO) { "cooldownAfterRequest 는 0 이상이어야 합니다. 받은 값: $cooldownAfterRequest" }
            require(cooldownAfterDeclined >= Duration.ZERO) { "cooldownAfterDeclined 는 0 이상이어야 합니다. 받은 값: $cooldownAfterDeclined" }
            require(cooldownAfterNegative >= Duration.ZERO) { "cooldownAfterNegative 는 0 이상이어야 합니다. 받은 값: $cooldownAfterNegative" }
            require(maxRequestsPerVersion >= 1) { "maxRequestsPerVersion 은 1 이상이어야 합니다. 받은 값: $maxRequestsPerVersion" }
        }
    }

    override fun evaluate(state: ReviewState, nowMillis: Long, appVersion: String): ReviewDecision {
        val first = state.firstLaunchAtMillis ?: return ReviewDecision.Deny(ReviewDenyReason.NO_LAUNCH_RECORDED)
        if (nowMillis - first < config.minDaysSinceFirstLaunch.days.inWholeMilliseconds) {
            return ReviewDecision.Deny(ReviewDenyReason.TOO_SOON_AFTER_FIRST_LAUNCH)
        }
        if (state.launchCount < config.minLaunchCount) return ReviewDecision.Deny(ReviewDenyReason.NOT_ENOUGH_LAUNCHES)
        if (state.significantEventCount < config.minSignificantEvents) {
            return ReviewDecision.Deny(ReviewDenyReason.NOT_ENOUGH_EVENTS)
        }
        if (isCoolingDown(state.lastNegativeAtMillis, config.cooldownAfterNegative, nowMillis)) {
            return ReviewDecision.Deny(ReviewDenyReason.RECENT_NEGATIVE_MOMENT)
        }
        if (isCoolingDown(state.lastDeclinedAtMillis, config.cooldownAfterDeclined, nowMillis)) {
            return ReviewDecision.Deny(ReviewDenyReason.RECENTLY_DECLINED)
        }
        if (isCoolingDown(state.lastRequestedAtMillis, config.cooldownAfterRequest, nowMillis)) {
            return ReviewDecision.Deny(ReviewDenyReason.RECENTLY_REQUESTED)
        }
        if (state.requestsFor(appVersion) >= config.maxRequestsPerVersion) {
            return ReviewDecision.Deny(ReviewDenyReason.VERSION_LIMIT_REACHED)
        }
        return ReviewDecision.Allow
    }

    private fun isCoolingDown(lastAtMillis: Long?, cooldown: Duration, nowMillis: Long): Boolean {
        if (lastAtMillis == null) return false
        return nowMillis - lastAtMillis < cooldown.inWholeMilliseconds
    }
}
