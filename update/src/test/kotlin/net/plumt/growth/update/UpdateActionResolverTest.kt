package net.plumt.growth.update

import org.junit.Assert.assertEquals
import org.junit.Test

/** 강제 판정 x Play 상태 결정 표. */
class UpdateActionResolverTest {

    private val installed = InstalledVersion("1.0.0", 10)
    private val required = ForceUpdateDecision.Required(installed, "2.0.0", null)
    private val recommended = ForceUpdateDecision.Recommended(installed, "1.5.0", null)
    private val upToDate = ForceUpdateDecision.UpToDate
    private val failed = ForceUpdateDecision.CheckFailed(UpdateException("실패"), CheckFailureReason.SOURCE_UNAVAILABLE)

    private fun available(
        priority: Int = 0,
        flexible: Boolean = true,
        immediate: Boolean = true,
        staleness: Int? = null,
    ) = PlayUpdateAvailability.Available(20, priority, staleness, flexible, immediate)

    private fun resolve(force: ForceUpdateDecision, play: PlayUpdateAvailability) =
        UpdateActionResolver.resolve(force, play)

    private val playFailed = PlayUpdateAvailability.Failed(UpdateException("Play 없음"))
    private val flexible = UpdateAction.StartPlay(PlayUpdateType.FLEXIBLE)
    private val immediate = UpdateAction.StartPlay(PlayUpdateType.IMMEDIATE)

    @Test
    fun `즉시 업데이트 진행 중이면 어떤 판정이든 이어간다`() {
        listOf(required, recommended, upToDate, failed).forEach {
            assertEquals(UpdateAction.ResumePlayImmediate, resolve(it, PlayUpdateAvailability.ImmediateInProgress))
        }
    }

    @Test
    fun `강제 - Play 에 새 버전이 있고 즉시 허용이면 즉시 업데이트`() {
        assertEquals(immediate, resolve(required, available()))
    }

    @Test
    fun `강제 - 즉시가 허용되지 않으면 스토어 차단`() {
        assertEquals(UpdateAction.BlockUntilStoreUpdate(required), resolve(required, available(immediate = false)))
    }

    @Test
    fun `강제 - Play 가 새 버전을 모르거나 실패하면 스토어 차단`() {
        listOf(
            PlayUpdateAvailability.NotAvailable,
            PlayUpdateAvailability.Unknown,
            PlayUpdateAvailability.Downloading,
            playFailed,
        ).forEach {
            assertEquals(UpdateAction.BlockUntilStoreUpdate(required), resolve(required, it))
        }
    }

    @Test
    fun `강제 - 내려받은 업데이트가 있으면 설치가 차단보다 먼저`() {
        assertEquals(UpdateAction.InstallDownloaded, resolve(required, PlayUpdateAvailability.Downloaded))
    }

    @Test
    fun `내려받은 업데이트는 강제가 아니어도 설치한다`() {
        listOf(upToDate, recommended, failed).forEach {
            assertEquals(UpdateAction.InstallDownloaded, resolve(it, PlayUpdateAvailability.Downloaded))
        }
    }

    @Test
    fun `내려받는 중이면 강제가 아닌 한 아무것도 하지 않는다`() {
        listOf(upToDate, recommended, failed).forEach {
            assertEquals(UpdateAction.None, resolve(it, PlayUpdateAvailability.Downloading))
        }
    }

    @Test
    fun `권장이 아니면 전략이 정한다 - 높은 우선순위는 즉시, 낮으면 유연`() {
        assertEquals(immediate, resolve(upToDate, available(priority = 4)))
        assertEquals(flexible, resolve(upToDate, available(priority = 3)))
        assertEquals(flexible, resolve(failed, available(priority = 1)))
    }

    @Test
    fun `전략이 권하지 않아도 권장 판정이면 유연 허용 시 유연 업데이트`() {
        val strict = PlayUpdateStrategy { null }
        assertEquals(flexible, UpdateActionResolver.resolve(recommended, available(), strict))
        assertEquals(UpdateAction.None, UpdateActionResolver.resolve(recommended, available(flexible = false), strict))
        assertEquals(UpdateAction.None, UpdateActionResolver.resolve(upToDate, available(), strict))
    }

    @Test
    fun `새 버전이 없거나 Play 가 모르면 아무것도 하지 않는다`() {
        listOf(upToDate, recommended, failed).forEach { force ->
            listOf(PlayUpdateAvailability.NotAvailable, PlayUpdateAvailability.Unknown, playFailed).forEach { play ->
                assertEquals(UpdateAction.None, resolve(force, play))
            }
        }
    }

    @Test
    fun `출처 실패는 막지 않는다 - fail-open`() {
        assertEquals(UpdateAction.None, resolve(failed, PlayUpdateAvailability.NotAvailable))
    }
}
