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
    fun `출처가 던지면 CheckFailed 에 원인이 보존된다`() = runBlocking {
        val boom = java.io.IOException("네트워크")
        val result = checker { throw boom }.check() as ForceUpdateDecision.CheckFailed
        assertEquals(boom, result.cause.cause)
    }

    @Test
    fun `출처가 잘못된 요구를 만들면 CheckFailed 이다`() = runBlocking {
        val result = checker { VersionRequirement(minimumVersionName = "not-a-version") }.check()
        assertTrue(result is ForceUpdateDecision.CheckFailed)
        assertTrue((result as ForceUpdateDecision.CheckFailed).cause.cause is IllegalArgumentException)
    }

    @Test
    fun `설치 버전 이름이 해석되지 않으면 CheckFailed 이다`() = runBlocking {
        val result = ForceUpdateChecker.create({ VersionRequirement(minimumVersionName = "1.0") }, InstalledVersion("dev", 1)).check()
        assertTrue(result is ForceUpdateDecision.CheckFailed)
    }

    @Test
    fun `취소는 삼키지 않고 전파한다`() {
        assertThrows(CancellationException::class.java) {
            runBlocking { checker { throw CancellationException("취소") }.check() }
        }
    }
}
