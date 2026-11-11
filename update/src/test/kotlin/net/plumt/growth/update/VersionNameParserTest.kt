package net.plumt.growth.update

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionNameParserTest {

    private fun prefix(name: String) = VersionNameParser.NUMERIC_PREFIX.parse(name)

    @Test
    fun `기본 규칙은 접미사를 버리고 숫자 구간만 읽는다`() {
        mapOf(
            "1.4.0-prod" to "1.4.0", "1.4.0-debug" to "1.4.0", "1.4.0 (45)" to "1.4.0", "1.4.0_beta" to "1.4.0",
            "1.4.0+build.7" to "1.4.0", "1.4.0-beta.1" to "1.4.0", "2.1" to "2.1", "7" to "7", "1.2.3.4" to "1.2.3.4",
            "  3.0.0  " to "3.0.0", "1.2.3-한글판" to "1.2.3", "1.2.3🌙" to "1.2.3", "1.2." to "1.2", "1.2.x" to "1.2",
            "0.0.0" to "0.0.0", "${Long.MAX_VALUE}" to "${Long.MAX_VALUE}",
        ).forEach { (name, expected) ->
            val v = prefix(name)
            assertEquals(name, expected, v.toString())
            assertTrue(name, !v.isPreRelease)
        }
        assertEquals(SemanticVersion.parse("1.4.0"), prefix("1.4.0-prod"))
    }

    @Test
    fun `숫자로 시작하지 않거나 구간이 다섯 개 이상이거나 Long 을 넘으면 IllegalArgumentException 이고 받은 값이 메시지에 있다`() {
        listOf("v1.2", "release", "", " ", ".1.2", "-1.0", "2026.11.03.1.2", "1.2.3.4.5-prod", "١٢٣").forEach { name ->
            val e = assertThrows(name, IllegalArgumentException::class.java) { prefix(name) }
            assertTrue(e.message!!, e.message!!.contains("\"$name\""))
        }
        assertThrows(IllegalArgumentException::class.java) { prefix("99999999999999999999.0") }
    }

    @Test
    fun `STRICT 는 SemVer 그대로 읽어 접미사를 사전 릴리스로 본다`() {
        val v = VersionNameParser.STRICT.parse(" 1.4.0-prod ")
        assertTrue(v.isPreRelease)
        assertTrue(v < SemanticVersion.parse("1.4.0"))
        assertThrows(IllegalArgumentException::class.java) { VersionNameParser.STRICT.parse("1.4.0 (45)") }
    }

    @Test
    fun `매우 긴 입력도 예외 대신 크래시 없이 처리한다`() {
        assertEquals("1.2", prefix("1.2" + "x".repeat(100_000)).toString())
        assertThrows(IllegalArgumentException::class.java) { prefix("1." + "1.".repeat(50_000)) }
    }

    @Test
    fun `임의 문자열에 IllegalArgumentException 외의 예외를 내지 않고 결과는 사전 릴리스가 아니다`() {
        val rnd = Random(7)
        val alphabet = "0123456789.-+_ ()vbeta한🌙\n"
        repeat(30_000) {
            val text = buildString { repeat(rnd.nextInt(20)) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
            try {
                val v = prefix(text)
                assertTrue(text, !v.isPreRelease && v.numericParts.size in 1..4)
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
