package net.plumt.growth.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ForceUpdateCheckerTest {

    private val installed = InstalledVersion("1.0.0", 10)

    private fun checker(source: MinimumVersionSource) = ForceUpdateChecker.create(source, installed)

    @Test
    fun `출처의 요구를 설치 버전에 적용한다`() = runBlocking {
        val required = checker { VersionRequirement(minimumVersionName = "1.1.0") }.check()
        assertEquals(ForceUpdateDecision.Required(installed, "1.1.0", null), required)
        assertEquals(ForceUpdateDecision.UpToDate, checker { VersionRequirement.NONE }.check())
    }

    @Test
    fun `한 자리 버전 이름도 메이저가 오르면 강제 업데이트다`() = runBlocking {
        val one = InstalledVersion("1", 1)
        val required = ForceUpdateChecker.create({ VersionRequirement(minimumVersionName = "2") }, one).check()
        assertEquals(ForceUpdateDecision.Required(one, "2", null), required)
        assertEquals(ForceUpdateDecision.UpToDate, ForceUpdateChecker.create({ VersionRequirement(minimumVersionName = "1") }, one).check())
        assertEquals(
            ForceUpdateDecision.Recommended(one, "2", null),
            ForceUpdateChecker.create({ VersionRequirement(recommendedVersionName = "2") }, one).check(),
        )
    }

    @Test
    fun `출처가 던지면 CheckFailed 에 원인이 보존된다`() = runBlocking {
        val boom = java.io.IOException("네트워크")
        val result = checker { throw boom }.check() as ForceUpdateDecision.CheckFailed
        assertEquals(boom, result.cause.cause)
        assertEquals(CheckFailureReason.SOURCE_UNAVAILABLE, result.reason)
    }

    @Test
    fun `출처가 잘못된 요구를 만들면 CheckFailed 이다`() = runBlocking {
        val result = checker { VersionRequirement(minimumVersionName = "not-a-version") }.check()
        assertTrue(result is ForceUpdateDecision.CheckFailed)
        result as ForceUpdateDecision.CheckFailed
        assertTrue(result.cause.cause is IllegalArgumentException)
        assertEquals("설정 오류는 네트워크 실패와 구분된다", CheckFailureReason.INVALID_REQUIREMENT, result.reason)
        assertTrue(result.cause.message!!.contains("not-a-version"))
    }

    @Test
    fun `H3 설치 버전 이름을 기본 규칙으로 해석하지 못하면 생성 시 IllegalArgumentException`() {
        listOf("dev", "v1.2", "2026.11.03.1.2", "").forEach { name ->
            if (name.isEmpty()) return@forEach // InstalledVersion 자체가 거부한다
            val e = assertThrows(IllegalArgumentException::class.java) {
                ForceUpdateChecker.create({ VersionRequirement(minimumVersionName = "1.0") }, InstalledVersion(name, 1))
            }
            assertTrue(e.message!!, e.message!!.contains(name))
        }
    }

    @Test
    fun `H3 교체한 해석 규칙을 쓴다`() = runBlocking {
        val dateParser = VersionNameParser { SemanticVersion.parse(it.replace("-", ".")) }
        val c = ForceUpdateChecker.create({ VersionRequirement(minimumVersionName = "2026.11.3") }, InstalledVersion("2026-11-02", 1), dateParser)
        assertTrue(c.check() is ForceUpdateDecision.Required)
        val strict = ForceUpdateChecker.create({ VersionRequirement(minimumVersionName = "1.4.0") }, InstalledVersion("1.4.0-prod", 1), VersionNameParser.STRICT)
        assertTrue("STRICT 는 접미사를 사전 릴리스로 본다", strict.check() is ForceUpdateDecision.Required)
    }

    @Test
    fun `생성 뒤 규칙이 다르게 답하면 INVALID_INSTALLED_VERSION`() = runBlocking {
        var calls = 0
        val flaky = VersionNameParser { if (calls++ == 0) SemanticVersion.parse("1.0") else throw IllegalArgumentException("두 번째는 실패") }
        val result = ForceUpdateChecker.create({ VersionRequirement(minimumVersionName = "1.0") }, installed, flaky).check()
        assertEquals(CheckFailureReason.INVALID_INSTALLED_VERSION, (result as ForceUpdateDecision.CheckFailed).reason)
        assertTrue(result.cause.cause is IllegalArgumentException)
    }

    @Test
    fun `H2 Remote Config 처럼 없는 키가 빈 문자열이어도 최소 버전 강제가 유지된다`() = runBlocking {
        val remote = mapOf("min_version" to "1.1.0")
        val source = MinimumVersionSource {
            VersionRequirement(
                minimumVersionName = remote["min_version"].orEmpty(),
                recommendedVersionName = remote["latest_version"].orEmpty(),
            )
        }
        assertEquals(ForceUpdateDecision.Required(installed, "1.1.0", null), checker(source).check())
    }

    @Test
    fun `취소는 삼키지 않고 전파한다`() {
        assertThrows(CancellationException::class.java) {
            runBlocking { checker { throw CancellationException("취소") }.check() }
        }
    }
}
