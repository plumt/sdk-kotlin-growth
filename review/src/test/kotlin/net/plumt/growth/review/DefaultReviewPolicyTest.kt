package net.plumt.growth.review

import kotlin.time.Duration.Companion.days
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DefaultReviewPolicyTest {

    private val day = 1.days.inWholeMilliseconds
    private val now = 1_800_000_000_000L
    private val version = "1.0.0"

    private val policy = DefaultReviewPolicy(
        DefaultReviewPolicy.Config(
            minDaysSinceFirstLaunch = 3,
            minLaunchCount = 5,
            minSignificantEvents = 3,
            cooldownAfterRequest = 60.days,
            cooldownAfterDeclined = 120.days,
            cooldownAfterNegative = 14.days,
            maxRequestsPerVersion = 1,
        ),
    )

    /** 모든 조건을 막 만족한 상태. */
    private val eligible = ReviewState(
        firstLaunchAtMillis = now - 10 * day,
        launchCount = 5,
        significantEventCount = 3,
    )

    private fun decide(state: ReviewState, appVersion: String = version) = policy.evaluate(state, now, appVersion)

    private fun deny(reason: ReviewDenyReason) = ReviewDecision.Deny(reason)

    @Test
    fun `모든 조건을 만족하면 허락한다`() {
        assertEquals(ReviewDecision.Allow, decide(eligible))
    }

    @Test
    fun `첫 실행 기록이 없으면 막는다`() {
        assertEquals(deny(ReviewDenyReason.NO_LAUNCH_RECORDED), decide(eligible.copy(firstLaunchAtMillis = null)))
    }

    @Test
    fun `설치 후 일수가 모자라면 막고 경계는 허락한다`() {
        assertEquals(
            deny(ReviewDenyReason.TOO_SOON_AFTER_FIRST_LAUNCH),
            decide(eligible.copy(firstLaunchAtMillis = now - 3 * day + 1)),
        )
        assertEquals(ReviewDecision.Allow, decide(eligible.copy(firstLaunchAtMillis = now - 3 * day)))
    }

    @Test
    fun `실행 횟수가 모자라면 막는다`() {
        assertEquals(deny(ReviewDenyReason.NOT_ENOUGH_LAUNCHES), decide(eligible.copy(launchCount = 4)))
    }

    @Test
    fun `의미 있는 사건이 모자라면 막는다`() {
        assertEquals(deny(ReviewDenyReason.NOT_ENOUGH_EVENTS), decide(eligible.copy(significantEventCount = 2)))
    }

    @Test
    fun `부정적 순간 직후에는 막고 쿨다운이 지나면 허락한다`() {
        assertEquals(
            deny(ReviewDenyReason.RECENT_NEGATIVE_MOMENT),
            decide(eligible.copy(lastNegativeAtMillis = now - 14 * day + 1)),
        )
        assertEquals(ReviewDecision.Allow, decide(eligible.copy(lastNegativeAtMillis = now - 14 * day)))
    }

    @Test
    fun `거절 직후에는 막고 쿨다운이 지나면 허락한다`() {
        assertEquals(
            deny(ReviewDenyReason.RECENTLY_DECLINED),
            decide(eligible.copy(lastDeclinedAtMillis = now - 119 * day)),
        )
        assertEquals(ReviewDecision.Allow, decide(eligible.copy(lastDeclinedAtMillis = now - 120 * day)))
    }

    @Test
    fun `요청 직후에는 막고 쿨다운이 지나면 허락한다`() {
        assertEquals(
            deny(ReviewDenyReason.RECENTLY_REQUESTED),
            decide(eligible.copy(lastRequestedAtMillis = now - 59 * day)),
        )
        // 다른 버전이면 버전 한도에 걸리지 않으므로 쿨다운만 본다
        assertEquals(
            ReviewDecision.Allow,
            decide(eligible.copy(lastRequestedAtMillis = now - 60 * day, versionOfRequests = "0.9.0", requestsInVersion = 1)),
        )
    }

    @Test
    fun `같은 버전에서 한도만큼 요청했으면 막고 새 버전이면 허락한다`() {
        val requested = eligible.copy(lastRequestedAtMillis = now - 100 * day, versionOfRequests = version, requestsInVersion = 1)
        assertEquals(deny(ReviewDenyReason.VERSION_LIMIT_REACHED), decide(requested))
        assertEquals(ReviewDecision.Allow, decide(requested, appVersion = "1.1.0"))
    }

    @Test
    fun `버전 한도를 2로 올리면 한 번 더 허락한다`() {
        val relaxed = DefaultReviewPolicy(DefaultReviewPolicy.Config(maxRequestsPerVersion = 2, minSignificantEvents = 0))
        val state = eligible.copy(lastRequestedAtMillis = now - 100 * day, versionOfRequests = version, requestsInVersion = 1)
        assertEquals(ReviewDecision.Allow, relaxed.evaluate(state, now, version))
        assertEquals(
            ReviewDecision.Deny(ReviewDenyReason.VERSION_LIMIT_REACHED),
            relaxed.evaluate(state.copy(requestsInVersion = 2), now, version),
        )
    }

    @Test
    fun `여러 조건에 걸리면 앞 순서의 이유를 돌려준다`() {
        val state = eligible.copy(launchCount = 0, lastNegativeAtMillis = now, lastDeclinedAtMillis = now)
        assertEquals(deny(ReviewDenyReason.NOT_ENOUGH_LAUNCHES), decide(state))
    }

    @Test
    fun `시계가 되감겨 사건이 미래로 보이면 쿨다운 중으로 본다`() {
        assertEquals(
            deny(ReviewDenyReason.RECENT_NEGATIVE_MOMENT),
            decide(eligible.copy(lastNegativeAtMillis = now + day)),
        )
    }

    @Test
    fun `설정 범위를 벗어나면 IllegalArgumentException 이다`() {
        assertThrows(IllegalArgumentException::class.java) { DefaultReviewPolicy.Config(minLaunchCount = -1) }
        assertThrows(IllegalArgumentException::class.java) { DefaultReviewPolicy.Config(maxRequestsPerVersion = 0) }
        assertThrows(IllegalArgumentException::class.java) { DefaultReviewPolicy.Config(cooldownAfterRequest = (-1).days) }
        assertThrows(IllegalArgumentException::class.java) { DefaultReviewPolicy.Config(minDaysSinceFirstLaunch = -1) }
    }

    @Test
    fun `기본 설정값은 문서와 같다`() {
        val c = DefaultReviewPolicy.Config()
        assertEquals(3, c.minDaysSinceFirstLaunch)
        assertEquals(5, c.minLaunchCount)
        assertEquals(3, c.minSignificantEvents)
        assertEquals(60.days, c.cooldownAfterRequest)
        assertEquals(120.days, c.cooldownAfterDeclined)
        assertEquals(14.days, c.cooldownAfterNegative)
        assertEquals(1, c.maxRequestsPerVersion)
        // 기본 정책 생성도 된다
        assertEquals(ReviewDecision.Allow, DefaultReviewPolicy().evaluate(eligible, now, version))
    }

    @Test
    fun `검증 메시지에 받은 값이 들어간다`() {
        assertEquals(
            "maxRequestsPerVersion 은 1 이상이어야 합니다. 받은 값: 0",
            assertThrows(IllegalArgumentException::class.java) { DefaultReviewPolicy.Config(maxRequestsPerVersion = 0) }.message,
        )
        assertEquals(
            "minSignificantEvents 는 0 이상이어야 합니다. 받은 값: -5",
            assertThrows(IllegalArgumentException::class.java) { DefaultReviewPolicy.Config(minSignificantEvents = -5) }.message,
        )
        assertThrows(IllegalArgumentException::class.java) { DefaultReviewPolicy.Config(cooldownAfterDeclined = (-1).days) }
        assertThrows(IllegalArgumentException::class.java) { DefaultReviewPolicy.Config(cooldownAfterNegative = (-1).days) }
    }

    @Test
    fun `임계값이 0 이면 조건이 없는 것과 같다`() {
        val open = DefaultReviewPolicy(
            DefaultReviewPolicy.Config(
                minDaysSinceFirstLaunch = 0, minLaunchCount = 0, minSignificantEvents = 0,
                cooldownAfterRequest = kotlin.time.Duration.ZERO, cooldownAfterDeclined = kotlin.time.Duration.ZERO,
                cooldownAfterNegative = kotlin.time.Duration.ZERO,
            ),
        )
        val state = ReviewState(firstLaunchAtMillis = now, lastRequestedAtMillis = now, lastDeclinedAtMillis = now, lastNegativeAtMillis = now)
        assertEquals(ReviewDecision.Allow, open.evaluate(state, now, version))
    }

    @Test
    fun `첫 실행이 미래이면 설치 후 일수 미달로 본다`() {
        assertEquals(
            deny(ReviewDenyReason.TOO_SOON_AFTER_FIRST_LAUNCH),
            decide(eligible.copy(firstLaunchAtMillis = now + day)),
        )
    }

    @Test
    fun `정책이 내는 거부 이유는 모두 도달 가능하다`() {
        val reached = setOf(
            decide(eligible.copy(firstLaunchAtMillis = null)),
            decide(eligible.copy(firstLaunchAtMillis = now)),
            decide(eligible.copy(launchCount = 0)),
            decide(eligible.copy(significantEventCount = 0)),
            decide(eligible.copy(lastNegativeAtMillis = now)),
            decide(eligible.copy(lastDeclinedAtMillis = now)),
            decide(eligible.copy(lastRequestedAtMillis = now - day)),
            decide(eligible.copy(versionOfRequests = version, requestsInVersion = 1)),
        ).map { (it as ReviewDecision.Deny).reason }.toSet()
        // REQUEST_IN_PROGRESS 는 정책이 아니라 ReviewPrompt 가 붙인다(ReviewRegressionTest)
        assertEquals(ReviewDenyReason.values().toSet() - ReviewDenyReason.REQUEST_IN_PROGRESS, reached)
    }
}
