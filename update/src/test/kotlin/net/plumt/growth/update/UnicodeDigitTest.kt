package net.plumt.growth.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 정규식 `\d` 는 Android 에서 유니코드 숫자('٣' 아랍-인도, '３' 전각)까지 맞춰 JVM 과 결과가 갈렸다.
 * 버전 숫자는 ASCII 만 받는다는 것을 고정한다.
 */
class UnicodeDigitTest {

    @Test
    fun `SemVer 는 유니코드 숫자를 받지 않는다`() {
        listOf("١.٢.٣", "１.0.0", "1.2.٣", "1.2.3-٣", "1.2.3-beta.٣", "1.٠.0").forEach {
            assertNull(it, SemanticVersion.parseOrNull(it))
            assertThrows(it, IllegalArgumentException::class.java) { SemanticVersion.parse(it) }
        }
        assertEquals("1.2.3-beta.3", SemanticVersion.parse("1.2.3-beta.3").toString())
    }

    @Test
    fun `숫자 구간 규칙은 ASCII 숫자만 숫자로 본다`() {
        val prefix = VersionNameParser.NUMERIC_PREFIX
        // 유니코드 숫자로 시작하면 숫자 구간이 없다
        listOf("٣.4.0", "３.4.0").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { prefix.parse(it) }
        }
        // 유니코드 숫자는 접미사처럼 버린다("1.4.0-prod" 와 같다)
        assertEquals("1.4.0", prefix.parse("1.4.0٣").toString())
        assertEquals("1.4", prefix.parse("1.4.٣").toString())
        assertThrows(IllegalArgumentException::class.java) { VersionNameParser.STRICT.parse("1.4.٣") }
    }
}
