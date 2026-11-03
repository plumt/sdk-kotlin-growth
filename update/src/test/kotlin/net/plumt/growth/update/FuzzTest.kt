package net.plumt.growth.update

import java.util.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 시드를 고정한 무작위 입력. 실패하면 같은 시드로 그대로 재현된다. */
class FuzzTest {

    private val alphabet = "0123456789.-+abvrcAZ _é🌙\n\u0000"

    private fun randomText(rnd: Random): String =
        buildString { repeat(rnd.nextInt(24)) { append(alphabet[rnd.nextInt(alphabet.length)]) } }

    private fun randomValid(rnd: Random): String {
        val nums = (0..rnd.nextInt(4)).joinToString(".") { rnd.nextInt(1000).toString() }
        val pre = if (rnd.nextInt(3) == 0) "-" + (0..rnd.nextInt(3)).joinToString(".") { if (rnd.nextBoolean()) rnd.nextInt(20).toString() else "rc" + rnd.nextInt(3) } else ""
        val meta = if (rnd.nextInt(4) == 0) "+b${rnd.nextInt(9)}" else ""
        return nums + pre + meta
    }

    @Test
    fun `임의 문자열 파싱은 IllegalArgumentException 외의 예외를 내지 않고 parseOrNull 과 일치한다`() {
        val rnd = Random(42)
        repeat(30_000) {
            val text = randomText(rnd)
            val orNull = SemanticVersion.parseOrNull(text)
            if (orNull == null) {
                try {
                    SemanticVersion.parse(text)
                    throw AssertionError("parse 가 던져야 합니다: '$text'")
                } catch (_: IllegalArgumentException) {
                }
            } else {
                assertEquals(orNull, SemanticVersion.parse(text))
                assertEquals(text, orNull.toString())
            }
        }
    }

    @Test
    fun `유효한 버전은 전순서를 이루고 equals 와 hashCode 가 일관된다`() {
        val rnd = Random(7)
        val versions = List(400) { SemanticVersion.parse(randomValid(rnd)) }
        repeat(20_000) {
            val a = versions[rnd.nextInt(versions.size)]
            val b = versions[rnd.nextInt(versions.size)]
            val c = versions[rnd.nextInt(versions.size)]
            assertEquals(Integer.signum(a.compareTo(b)), -Integer.signum(b.compareTo(a)))
            assertEquals(0, a.compareTo(a))
            if (a <= b && b <= c) assertTrue("추이성: $a $b $c", a <= c)
            if (a.compareTo(b) == 0) {
                assertEquals(a, b)
                assertEquals(a.hashCode(), b.hashCode())
            }
        }
    }

    @Test
    fun `임의 요구와 설치 버전의 판정은 항상 예외 없이 끝나고 우선순위를 지킨다`() = runBlocking {
        val rnd = Random(99)
        repeat(5_000) {
            val installed = InstalledVersion(randomValid(rnd), rnd.nextInt(500).toLong())
            val req = VersionRequirement(
                minimumVersionName = if (rnd.nextBoolean()) randomValid(rnd) else null,
                minimumVersionCode = if (rnd.nextBoolean()) rnd.nextInt(500).toLong() else null,
                recommendedVersionName = if (rnd.nextBoolean()) randomValid(rnd) else null,
                recommendedVersionCode = if (rnd.nextBoolean()) rnd.nextInt(500).toLong() else null,
            )
            val d = req.evaluate(installed)
            val belowMin = (req.minimumVersionName?.let { SemanticVersion.parse(installed.versionName) < SemanticVersion.parse(it) } ?: false) ||
                (req.minimumVersionCode?.let { installed.versionCode < it } ?: false)
            assertEquals(belowMin, d is ForceUpdateDecision.Required)
            assertEquals(d, ForceUpdateChecker.create({ req }, installed).check())
            // 추가 확인: 단조성 — 설치 코드를 올리면 Required 에서 벗어날 수만 있다
        }
    }

    @Test
    fun `임의 문자열 요구는 생성에서 IllegalArgumentException 만 낸다`() {
        val rnd = Random(5)
        var rejected = 0
        repeat(10_000) {
            try {
                assertNotNull(VersionRequirement(minimumVersionName = randomText(rnd)))
            } catch (_: IllegalArgumentException) {
                rejected++
            }
        }
        assertTrue(rejected > 0)
        assertNull(SemanticVersion.parseOrNull("1.2.3-"))
    }
}
