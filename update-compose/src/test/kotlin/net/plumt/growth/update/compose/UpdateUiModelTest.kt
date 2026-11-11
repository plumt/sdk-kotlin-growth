package net.plumt.growth.update.compose

import net.plumt.growth.update.CheckFailurePolicy
import net.plumt.growth.update.CheckFailureReason
import net.plumt.growth.update.ForceUpdateDecision
import net.plumt.growth.update.InstalledVersion
import net.plumt.growth.update.PlayUpdateAvailability
import net.plumt.growth.update.PlayUpdateType
import net.plumt.growth.update.UpdateAction
import net.plumt.growth.update.UpdateActionResolver
import net.plumt.growth.update.UpdateCheckResult
import net.plumt.growth.update.UpdateException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateUiModelTest {

    private val installed = InstalledVersion("1.0", 1)
    private val required = ForceUpdateDecision.Required(installed, "2.0", null)
    private val recommended = ForceUpdateDecision.Recommended(installed, "1.5", null)
    private val failed = ForceUpdateDecision.CheckFailed(UpdateException("오프라인"), CheckFailureReason.SOURCE_UNAVAILABLE)
    private val available = PlayUpdateAvailability.Available(2, 0, null, flexibleAllowed = true, immediateAllowed = true)
    private val flexible = UpdateAction.StartPlay(PlayUpdateType.FLEXIBLE)
    private val immediate = UpdateAction.StartPlay(PlayUpdateType.IMMEDIATE)
    private val block = UpdateAction.BlockUntilStoreUpdate(required)

    private val allActions = listOf(
        UpdateAction.None, flexible, immediate, UpdateAction.ResumePlayImmediate, UpdateAction.InstallDownloaded, block,
        UpdateAction.BlockUntilCheckSucceeds(failed),
    )
    private val allPlay = listOf(
        PlayUpdateAvailability.NotAvailable, PlayUpdateAvailability.Unknown, available, PlayUpdateAvailability.ImmediateInProgress,
        PlayUpdateAvailability.Downloading, PlayUpdateAvailability.Downloaded, PlayUpdateAvailability.Failed(UpdateException("Play 없음")),
    )

    private fun ui(force: ForceUpdateDecision, action: UpdateAction) =
        UpdateUiModel.from(UpdateCheckResult(force, PlayUpdateAvailability.NotAvailable, action))

    @Test
    fun `강제가 아니면 동작별 화면 모델 표`() {
        assertEquals(UpdateUiModel.Hidden, ui(ForceUpdateDecision.UpToDate, UpdateAction.None))
        assertEquals(UpdateUiModel.Hidden, ui(ForceUpdateDecision.UpToDate, UpdateAction.ResumePlayImmediate))
        assertEquals(UpdateUiModel.Hidden, ui(ForceUpdateDecision.UpToDate, immediate))
        assertEquals(UpdateUiModel.ReadyToInstall, ui(recommended, UpdateAction.InstallDownloaded))
        assertEquals(UpdateUiModel.Optional(flexible), ui(recommended, flexible))
        assertEquals(UpdateUiModel.CheckFailed(failed), ui(failed, UpdateAction.BlockUntilCheckSucceeds(failed)))
    }

    @Test
    fun `H1 강제 판정이면 Play 동작이 무엇이든 차단이고 확인 버튼은 그 동작을 실행한다`() {
        allActions.forEach { action ->
            val m = ui(required, action)
            assertEquals("동작 $action", UpdateUiModel.Blocking(required, action), m)
        }
        assertTrue((ui(required, block) as UpdateUiModel.Blocking).opensStore)
        assertFalse((ui(required, immediate) as UpdateUiModel.Blocking).opensStore)
        assertFalse((ui(required, UpdateAction.InstallDownloaded) as UpdateUiModel.Blocking).opensStore)
    }

    @Test
    fun `H1 결정표를 거친 모든 강제 조합은 차단 화면이다 - 어떤 Play 상태에서도 본문이 열리지 않는다`() {
        allPlay.forEach { play ->
            val action = UpdateActionResolver.resolve(required, play)
            val m = UpdateUiModel.from(UpdateCheckResult(required, play, action))
            assertTrue("Play $play → $action → $m", m is UpdateUiModel.Blocking)
            assertTrue(UpdateCheckResult(required, play, action).isBlocking)
        }
    }

    @Test
    fun `H1 Play 흐름이 취소된 뒤 다시 확인해도 강제면 차단이 유지된다`() {
        // 즉시 업데이트 시작 → 사용자가 뒤로 가기 → onResume refresh: Play 는 여전히 새 버전 있음
        val again = UpdateActionResolver.resolve(required, available)
        assertEquals(immediate, again)
        assertTrue(UpdateUiModel.from(UpdateCheckResult(required, available, again)) is UpdateUiModel.Blocking)
        // 프로세스 종료 후 재실행: 진행 중이던 즉시 업데이트
        val resumed = UpdateActionResolver.resolve(required, PlayUpdateAvailability.ImmediateInProgress)
        assertTrue(UpdateUiModel.from(UpdateCheckResult(required, PlayUpdateAvailability.ImmediateInProgress, resumed)) is UpdateUiModel.Blocking)
    }

    @Test
    fun `강제가 아닌데 차단 동작만 들어와도 안전한 쪽으로 차단한다`() {
        assertEquals(UpdateUiModel.Blocking(required, block), ui(ForceUpdateDecision.UpToDate, block))
    }

    @Test
    fun `실패 정책이 막으면 다시 확인 화면, 막지 않으면 Play 결과대로`() {
        val closed = UpdateActionResolver.resolve(failed, available, onCheckFailure = CheckFailurePolicy.FAIL_CLOSED)
        assertEquals(UpdateUiModel.CheckFailed(failed), UpdateUiModel.from(UpdateCheckResult(failed, available, closed)))
        val open = UpdateActionResolver.resolve(failed, available)
        assertEquals(UpdateUiModel.Optional(flexible), UpdateUiModel.from(UpdateCheckResult(failed, available, open)))
    }
}
