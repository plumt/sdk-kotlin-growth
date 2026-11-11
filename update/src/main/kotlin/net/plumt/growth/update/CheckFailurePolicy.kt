package net.plumt.growth.update

/**
 * 원격 판정이 실패했을 때([ForceUpdateDecision.CheckFailed]) 앱을 막을지 정한다. [AppUpdater.create]·[UpdateActionResolver.resolve] 에 넘긴다.
 *
 * 막기로 하면 결정표가 [UpdateAction.BlockUntilCheckSucceeds] 를 내고, 앱은 다시 확인하기 전까지 본문을 열지 않는다.
 * 기본은 [FAIL_OPEN] 이다 — 오프라인·일시 장애로 앱을 쓰지 못하게 하지 않는다. 실패는 여전히 [UpdateCheckResult.force] 에 남는다.
 *
 * 구현은 부수효과가 없는 순수 함수여야 한다.
 */
public fun interface CheckFailurePolicy {

    /** @return `true` 면 [failure] 동안 앱을 막는다 */
    public fun shouldBlock(failure: ForceUpdateDecision.CheckFailed): Boolean

    public companion object {
        /** 어떤 실패에도 막지 않는다(기본). */
        @JvmField
        public val FAIL_OPEN: CheckFailurePolicy = CheckFailurePolicy { false }

        /** 모든 실패에 막는다. 오프라인 사용자도 막히므로 재시도 동작을 반드시 준다. */
        @JvmField
        public val FAIL_CLOSED: CheckFailurePolicy = CheckFailurePolicy { true }

        /**
         * 설정 오류([CheckFailureReason.INVALID_REQUIREMENT]·[CheckFailureReason.INVALID_INSTALLED_VERSION])일 때만 막고
         * 네트워크 실패([CheckFailureReason.SOURCE_UNAVAILABLE])는 통과시킨다.
         */
        @JvmField
        public val FAIL_CLOSED_ON_INVALID_VALUE: CheckFailurePolicy =
            CheckFailurePolicy { it.reason != CheckFailureReason.SOURCE_UNAVAILABLE }
    }
}
