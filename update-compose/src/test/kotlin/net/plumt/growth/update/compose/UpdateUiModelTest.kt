package net.plumt.growth.update.compose

import net.plumt.growth.update.ForceUpdateDecision
import net.plumt.growth.update.InstalledVersion
import net.plumt.growth.update.PlayUpdateAvailability
import net.plumt.growth.update.PlayUpdateType
import net.plumt.growth.update.UpdateAction
import net.plumt.growth.update.UpdateCheckResult
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateUiModelTest {

    private val required = ForceUpdateDecision.Required(InstalledVersion("1.0", 1), "2.0", null)

    @Test
    fun `동작별 화면 모델 표`() {
        assertEquals(UpdateUiModel.Hidden, UpdateUiModel.from(UpdateAction.None))
        assertEquals(UpdateUiModel.Hidden, UpdateUiModel.from(UpdateAction.ResumePlayImmediate))
        assertEquals(UpdateUiModel.Hidden, UpdateUiModel.from(UpdateAction.StartPlay(PlayUpdateType.IMMEDIATE)))
        assertEquals(UpdateUiModel.ReadyToInstall, UpdateUiModel.from(UpdateAction.InstallDownloaded))
        val flexible = UpdateAction.StartPlay(PlayUpdateType.FLEXIBLE)
        assertEquals(UpdateUiModel.Optional(flexible), UpdateUiModel.from(flexible))
        val block = UpdateAction.BlockUntilStoreUpdate(required)
        assertEquals(UpdateUiModel.Blocking(block), UpdateUiModel.from(block))
    }

    @Test
    fun `확인 결과에서도 같은 규칙을 쓴다`() {
        val block = UpdateAction.BlockUntilStoreUpdate(required)
        val result = UpdateCheckResult(required, PlayUpdateAvailability.NotAvailable, block)
        assertEquals(UpdateUiModel.Blocking(block), UpdateUiModel.from(result))
    }
}
