package net.plumt.growth.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DefaultPlayUpdateStrategyTest {

    private fun available(priority: Int = 0, staleness: Int? = null, flexible: Boolean = true, immediate: Boolean = true) =
        PlayUpdateAvailability.Available(2, priority, staleness, flexible, immediate)

    @Test
    fun `기본값 - 우선순위 4 이상은 즉시, 아니면 유연`() {
        val s = DefaultPlayUpdateStrategy()
        assertEquals(PlayUpdateType.IMMEDIATE, s.choose(available(priority = 5)))
        assertEquals(PlayUpdateType.IMMEDIATE, s.choose(available(priority = 4)))
        assertEquals(PlayUpdateType.FLEXIBLE, s.choose(available(priority = 3)))
    }

    @Test
    fun `즉시가 허용되지 않으면 높은 우선순위도 유연으로 내려간다`() {
        assertEquals(PlayUpdateType.FLEXIBLE, DefaultPlayUpdateStrategy().choose(available(priority = 5, immediate = false)))
    }

    @Test
    fun `경과 일수가 기준을 넘으면 즉시로 올린다`() {
        val s = DefaultPlayUpdateStrategy(DefaultPlayUpdateStrategy.Config(immediateMinStalenessDays = 30))
        assertEquals(PlayUpdateType.IMMEDIATE, s.choose(available(staleness = 30)))
        assertEquals(PlayUpdateType.FLEXIBLE, s.choose(available(staleness = 29)))
        assertEquals(PlayUpdateType.FLEXIBLE, s.choose(available(staleness = null)))
    }

    @Test
    fun `유연 최소 경과 일수를 두면 그 전에는 권하지 않는다`() {
        val s = DefaultPlayUpdateStrategy(DefaultPlayUpdateStrategy.Config(flexibleMinStalenessDays = 3))
        assertNull(s.choose(available(staleness = 2)))
        assertNull(s.choose(available(staleness = null)))
        assertEquals(PlayUpdateType.FLEXIBLE, s.choose(available(staleness = 3)))
    }

    @Test
    fun `유연 최소 우선순위와 허용 여부`() {
        val s = DefaultPlayUpdateStrategy(DefaultPlayUpdateStrategy.Config(flexibleMinPriority = 2))
        assertNull(s.choose(available(priority = 1)))
        assertEquals(PlayUpdateType.FLEXIBLE, s.choose(available(priority = 2)))
        assertNull(DefaultPlayUpdateStrategy().choose(available(flexible = false, immediate = false)))
    }

    @Test
    fun `설정 범위를 벗어나면 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { DefaultPlayUpdateStrategy.Config(immediateMinPriority = -1) }
        assertThrows(IllegalArgumentException::class.java) { DefaultPlayUpdateStrategy.Config(immediateMinStalenessDays = -1) }
        assertThrows(IllegalArgumentException::class.java) { DefaultPlayUpdateStrategy.Config(flexibleMinStalenessDays = -1) }
    }
}
