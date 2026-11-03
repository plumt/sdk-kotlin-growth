package net.plumt.growth.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VersionRequirementTest {

    private val installed = InstalledVersion("1.5.0", 50)

    @Test
    fun `요구가 없으면 UpToDate`() {
        assertEquals(ForceUpdateDecision.UpToDate, VersionRequirement.NONE.evaluate(installed))
    }

    @Test
    fun `최소 버전 이름 비교 - 낮으면 Required 같거나 높으면 통과`() {
        assertEquals(
            ForceUpdateDecision.Required(installed, "1.5.1", null),
            VersionRequirement(minimumVersionName = "1.5.1").evaluate(installed),
        )
        assertEquals(ForceUpdateDecision.UpToDate, VersionRequirement(minimumVersionName = "1.5.0").evaluate(installed))
        assertEquals(ForceUpdateDecision.UpToDate, VersionRequirement(minimumVersionName = "1.4.9").evaluate(installed))
        assertEquals(ForceUpdateDecision.UpToDate, VersionRequirement(minimumVersionName = "1.5").evaluate(installed))
    }

    @Test
    fun `최소 버전 코드 비교`() {
        assertEquals(
            ForceUpdateDecision.Required(installed, null, 51),
            VersionRequirement(minimumVersionCode = 51).evaluate(installed),
        )
        assertEquals(ForceUpdateDecision.UpToDate, VersionRequirement(minimumVersionCode = 50).evaluate(installed))
    }

    @Test
    fun `이름과 코드가 엇갈리면 하나라도 모자라면 Required`() {
        assertEquals(
            ForceUpdateDecision.Required(installed, "1.0.0", 60),
            VersionRequirement(minimumVersionName = "1.0.0", minimumVersionCode = 60).evaluate(installed),
        )
        assertEquals(
            ForceUpdateDecision.Required(installed, "2.0.0", 10),
            VersionRequirement(minimumVersionName = "2.0.0", minimumVersionCode = 10).evaluate(installed),
        )
    }

    @Test
    fun `권장만 모자라면 Recommended`() {
        assertEquals(
            ForceUpdateDecision.Recommended(installed, "1.6.0", null),
            VersionRequirement(minimumVersionName = "1.0.0", recommendedVersionName = "1.6.0").evaluate(installed),
        )
        assertEquals(
            ForceUpdateDecision.Recommended(installed, null, 70),
            VersionRequirement(recommendedVersionCode = 70).evaluate(installed),
        )
    }

    @Test
    fun `최소와 권장이 모두 모자라면 Required 가 이긴다`() {
        val result = VersionRequirement(minimumVersionName = "2.0.0", recommendedVersionName = "3.0.0").evaluate(installed)
        assertEquals(ForceUpdateDecision.Required(installed, "2.0.0", null), result)
    }

    @Test
    fun `사전 릴리스 설치본은 같은 숫자의 최소 버전보다 낮다`() {
        val beta = InstalledVersion("2.0.0-beta.1", 100)
        assertEquals(
            ForceUpdateDecision.Required(beta, "2.0.0", null),
            VersionRequirement(minimumVersionName = "2.0.0").evaluate(beta),
        )
    }

    @Test
    fun `잘못된 값은 생성 시 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { VersionRequirement(minimumVersionName = "latest") }
        assertThrows(IllegalArgumentException::class.java) { VersionRequirement(recommendedVersionName = "") }
        assertThrows(IllegalArgumentException::class.java) { VersionRequirement(minimumVersionCode = -1) }
        assertThrows(IllegalArgumentException::class.java) { InstalledVersion("", 1) }
        assertThrows(IllegalArgumentException::class.java) { InstalledVersion("1.0", -1) }
    }

    @Test
    fun `설치 버전 이름을 해석할 수 없으면 evaluate 가 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            VersionRequirement(minimumVersionName = "1.0").evaluate(InstalledVersion("dev", 1))
        }
    }
}
