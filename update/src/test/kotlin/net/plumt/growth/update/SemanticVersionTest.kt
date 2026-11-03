package net.plumt.growth.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {

    private fun v(text: String) = SemanticVersion.parse(text)

    @Test
    fun `숫자 구간은 숫자로 비교한다 - 문자열 비교였다면 틀리는 경우`() {
        assertTrue(v("1.10.0") > v("1.9.0"))
        assertTrue(v("2.0.0") > v("1.99.99"))
        assertTrue(v("10.0") > v("9.9.9"))
    }

    @Test
    fun `모자란 구간은 0 으로 보고 같다`() {
        assertEquals(v("1.2"), v("1.2.0"))
        assertEquals(v("1.2"), v("1.2.0.0"))
        assertEquals(v("1.2").hashCode(), v("1.2.0").hashCode())
        assertTrue(v("1.2.0.1") > v("1.2"))
    }

    @Test
    fun `사전 릴리스는 같은 숫자의 정식 버전보다 낮다`() {
        assertTrue(v("1.0.0-beta") < v("1.0.0"))
        assertTrue(v("1.0.0-alpha") < v("1.0.0-beta"))
        assertTrue(v("1.0.0-beta.2") < v("1.0.0-beta.11"))
        assertTrue(v("1.0.0-1") < v("1.0.0-alpha"))
        assertTrue(v("1.0.0-alpha") < v("1.0.0-alpha.1"))
        assertTrue(v("1.0.0-rc.1") < v("1.0.1"))
        assertTrue(v("1.0.0-beta").isPreRelease)
    }

    @Test
    fun `빌드 메타데이터는 비교에서 무시한다`() {
        assertEquals(v("1.0.0+abc"), v("1.0.0+xyz"))
        assertEquals(v("1.0.0"), v("1.0.0+5"))
    }

    @Test
    fun `원본 문자열을 toString 으로 돌려준다`() {
        assertEquals("1.2.3-beta.1+7", v("1.2.3-beta.1+7").toString())
        assertEquals(listOf(1L, 2L, 3L), v("1.2.3").numericParts)
    }

    @Test
    fun `해석할 수 없으면 IllegalArgumentException 이고 받은 값이 메시지에 있다`() {
        listOf("", " ", "abc", "1..2", "1.", ".1", "v1.2", "1.2.3.4.5", "1.2.x", "99999999999999999999.0", "1.0-", "1.0 ").forEach {
            val e = assertThrows("'$it'", IllegalArgumentException::class.java) { SemanticVersion.parse(it) }
            assertTrue(e.message!!.contains("\"$it\""))
            assertNull(SemanticVersion.parseOrNull(it))
        }
    }
}
