package net.plumt.growth.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    fun `STRICT 규칙이면 사전 릴리스 설치본은 같은 숫자의 최소 버전보다 낮다`() {
        val beta = InstalledVersion("2.0.0-beta.1", 100)
        assertEquals(
            ForceUpdateDecision.Required(beta, "2.0.0", null),
            VersionRequirement(minimumVersionName = "2.0.0").evaluate(beta, VersionNameParser.STRICT),
        )
    }

    @Test
    fun `기본 규칙은 설치본 접미사를 버려 같은 숫자의 최소 버전을 통과한다`() {
        listOf("2.0.0-beta.1", "2.0.0-prod", "2.0.0-debug", "2.0.0 (45)", "2.0.0_rc", "2.0.0+7", " 2.0.0 ").forEach {
            assertEquals(it, ForceUpdateDecision.UpToDate, VersionRequirement(minimumVersionName = "2.0.0").evaluate(InstalledVersion(it, 1)))
            assertTrue(it, VersionRequirement(minimumVersionName = "2.0.1").evaluate(InstalledVersion(it, 1)) is ForceUpdateDecision.Required)
        }
    }

    @Test
    fun `잘못된 값은 생성 시 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { VersionRequirement(minimumVersionName = "latest") }
        assertThrows(IllegalArgumentException::class.java) { VersionRequirement(recommendedVersionName = "v2.0.0") }
        assertThrows(IllegalArgumentException::class.java) { VersionRequirement(recommendedVersionCode = -1) }
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

    @Test
    fun `H2 빈 값과 공백은 필드마다 설정 없음이고 다른 필드는 살아 있다`() {
        listOf("", " ", "\t\n").forEach { blank ->
            val r = VersionRequirement(minimumVersionName = blank, minimumVersionCode = 5, recommendedVersionName = blank)
            assertNull(r.minimumVersionName)
            assertNull(r.recommendedVersionName)
            assertEquals(ForceUpdateDecision.Required(InstalledVersion("9.9", 4), null, 5L), r.evaluate(InstalledVersion("9.9", 4)))
            assertEquals(ForceUpdateDecision.UpToDate, r.evaluate(InstalledVersion("9.9", 5)))
            assertTrue(VersionRequirement(minimumVersionName = blank, recommendedVersionName = blank).isEmpty)
        }
        val onlyRecommended = VersionRequirement(minimumVersionName = "", recommendedVersionName = "1.5")
        assertTrue(onlyRecommended.evaluate(InstalledVersion("1.4.9", 1)) is ForceUpdateDecision.Recommended)
    }

    @Test
    fun `H2 앞뒤 공백은 떼고 저장한다`() {
        val r = VersionRequirement(minimumVersionName = " 2.0.0 ", recommendedVersionName = "\t3.0\n")
        assertEquals("2.0.0", r.minimumVersionName)
        assertEquals("3.0", r.recommendedVersionName)
        assertEquals(ForceUpdateDecision.Required(installed, "2.0.0", null), r.evaluate(installed))
    }

    @Test
    fun `H2 형식 오류는 필드 이름과 받은 값을 메시지에 담는다`() {
        val min = assertThrows(IllegalArgumentException::class.java) { VersionRequirement(minimumVersionName = "v2.0.0") }
        assertTrue(min.message!!, min.message!!.contains("minimumVersionName") && min.message!!.contains("v2.0.0"))
        val rec = assertThrows(IllegalArgumentException::class.java) { VersionRequirement(recommendedVersionName = "1.2.3.4.5") }
        assertTrue(rec.message!!, rec.message!!.contains("recommendedVersionName") && rec.message!!.contains("1.2.3.4.5"))
    }

    @Test
    fun `이름 요구가 없으면 설치 버전 이름을 해석하지 않는다`() {
        val codeOnly = VersionRequirement(minimumVersionCode = 3)
        assertEquals(ForceUpdateDecision.UpToDate, codeOnly.evaluate(InstalledVersion("개발판", 3)))
        assertTrue(codeOnly.evaluate(InstalledVersion("개발판", 2)) is ForceUpdateDecision.Required)
    }

    @Test
    fun `값 동등성은 정규화된 값으로 판단한다`() {
        val a = VersionRequirement(minimumVersionName = " 1.0 ", recommendedVersionCode = 2)
        val b = VersionRequirement(minimumVersionName = "1.0", recommendedVersionCode = 2)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(VersionRequirement(), VersionRequirement(minimumVersionName = ""))
        assertNotEquals(a, VersionRequirement(minimumVersionName = "1.0"))
        assertNotEquals(a, VersionRequirement(minimumVersionName = "1.0", minimumVersionCode = 1, recommendedVersionCode = 2))
        assertNotEquals(a, VersionRequirement(minimumVersionName = "1.0", recommendedVersionName = "1.1", recommendedVersionCode = 2))
        assertFalse((a as Any) == "1.0")
        assertEquals(
            "VersionRequirement(minimumVersionName=1.0, minimumVersionCode=null, recommendedVersionName=null, recommendedVersionCode=2)",
            a.toString(),
        )
        assertTrue(VersionRequirement.NONE.isEmpty)
        assertFalse(VersionRequirement(recommendedVersionCode = 1).isEmpty)
        assertFalse(VersionRequirement(minimumVersionCode = 1).isEmpty)
        assertFalse(VersionRequirement(recommendedVersionName = "1").isEmpty)
    }
}
