package net.plumt.growth.review

/**
 * 정책이 쓰는 시각의 출처. 시스템 시각을 직접 부르지 않고 이 인터페이스로만 얻어, 테스트에서 시간을 고정할 수 있다.
 */
public fun interface ReviewClock {

    /** 현재 시각(epoch millis). */
    public fun nowMillis(): Long

    public companion object {
        /** `System.currentTimeMillis()` 를 쓰는 기본 구현. */
        @JvmField
        public val System: ReviewClock = ReviewClock { java.lang.System.currentTimeMillis() }
    }
}
