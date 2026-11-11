package net.plumt.growth.update.compose

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.plumt.growth.update.CheckFailureReason
import net.plumt.growth.update.ForceUpdateDecision
import net.plumt.growth.update.PlayUpdateAvailability
import net.plumt.growth.update.UpdateCheckResult
import net.plumt.growth.update.UpdateException
import net.plumt.growth.update.InstalledVersion
import net.plumt.growth.update.PlayUpdateType
import net.plumt.growth.update.UpdateAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateGateTest {

    @get:Rule
    val rule = createComposeRule()

    private val texts = UpdateTexts(
        "선택 제목", "선택 설명", "준비 제목", "준비 설명", "필수 제목", "필수 설명", "실패 제목", "실패 설명", "확인", "재시작", "나중에", "재시도",
    )
    private val optional = UpdateUiModel.Optional(UpdateAction.StartPlay(PlayUpdateType.FLEXIBLE))
    private val required = ForceUpdateDecision.Required(InstalledVersion("1.0", 1), "2.0", null)
    private val blocking = UpdateUiModel.Blocking(required, UpdateAction.BlockUntilStoreUpdate(required))

    private var confirms = 0
    private var dismisses = 0

    private fun show(model: UpdateUiModel) = rule.setContent {
        UpdateGate(model, onConfirm = { confirms++ }, onDismiss = { dismisses++ }, texts = texts) { Text("본문") }
    }

    @Test
    fun `Hidden 이면 본문만 보인다`() {
        show(UpdateUiModel.Hidden)
        rule.onNodeWithText("본문").assertIsDisplayed()
        rule.onNodeWithTag(UpdateGateTags.DIALOG_CONFIRM).assertDoesNotExist()
    }

    @Test
    fun `Optional 이면 본문 위에 다이얼로그가 뜨고 확인과 나중에가 콜백을 부른다`() {
        show(optional)
        rule.onNodeWithText("본문").assertIsDisplayed()
        rule.onNodeWithText("선택 제목").assertIsDisplayed()
        rule.onNodeWithTag(UpdateGateTags.DIALOG_CONFIRM).performClick()
        rule.onNodeWithTag(UpdateGateTags.DIALOG_LATER).performClick()
        assertEquals(1, confirms)
        assertEquals(1, dismisses)
    }

    @Test
    fun `ReadyToInstall 은 설치 문구를 쓴다`() {
        show(UpdateUiModel.ReadyToInstall)
        rule.onNodeWithText("준비 설명").assertIsDisplayed()
        rule.onNodeWithText("재시작").assertIsDisplayed()
    }

    @Test
    fun `Blocking 이면 본문을 그리지 않고 닫는 버튼도 없다`() {
        show(blocking)
        rule.onNodeWithText("본문").assertDoesNotExist()
        rule.onNodeWithTag(UpdateGateTags.BLOCKING_SCREEN).assertIsDisplayed()
        rule.onNodeWithTag(UpdateGateTags.DIALOG_LATER).assertDoesNotExist()
        rule.onNodeWithTag(UpdateGateTags.BLOCKING_CONFIRM).performClick()
        assertEquals(1, confirms)
        assertEquals(0, dismisses)
    }

    @Test
    fun `H1 강제 중 Play 즉시 업데이트 동작이어도 본문을 그리지 않는다`() {
        listOf(UpdateAction.StartPlay(PlayUpdateType.IMMEDIATE), UpdateAction.ResumePlayImmediate, UpdateAction.InstallDownloaded).forEach {
            val model = UpdateUiModel.from(UpdateCheckResult(required, PlayUpdateAvailability.NotAvailable, it))
            assertTrue(model is UpdateUiModel.Blocking)
        }
        show(UpdateUiModel.Blocking(required, UpdateAction.StartPlay(PlayUpdateType.IMMEDIATE)))
        rule.onNodeWithText("본문").assertDoesNotExist()
        rule.onNodeWithTag(UpdateGateTags.BLOCKING_SCREEN).assertIsDisplayed()
        rule.onNodeWithTag(UpdateGateTags.DIALOG_LATER).assertDoesNotExist()
    }

    @Test
    fun `H1 모델이 바뀌어도 강제인 동안 본문은 한 번도 그려지지 않는다`() {
        var model by mutableStateOf<UpdateUiModel>(UpdateUiModel.Blocking(required, UpdateAction.StartPlay(PlayUpdateType.IMMEDIATE)))
        var contentDrawn = 0
        rule.setContent {
            UpdateGate(model, onConfirm = {}, onDismiss = {}, texts = texts) { contentDrawn++; Text("본문") }
        }
        // Play 화면 취소 → refresh → 내려받은 업데이트 → 재개
        model = UpdateUiModel.Blocking(required, UpdateAction.InstallDownloaded)
        rule.waitForIdle()
        model = UpdateUiModel.Blocking(required, UpdateAction.ResumePlayImmediate)
        rule.waitForIdle()
        assertEquals(0, contentDrawn)
        rule.onNodeWithTag(UpdateGateTags.BLOCKING_SCREEN).assertIsDisplayed()
    }

    @Test
    fun `CheckFailed 면 본문 없이 다시 시도 버튼만 있다`() {
        val failed = ForceUpdateDecision.CheckFailed(UpdateException("오프라인"), CheckFailureReason.SOURCE_UNAVAILABLE)
        show(UpdateUiModel.CheckFailed(failed))
        rule.onNodeWithText("본문").assertDoesNotExist()
        rule.onNodeWithText("실패 제목").assertIsDisplayed()
        rule.onNodeWithText("재시도").assertIsDisplayed()
        rule.onNodeWithTag(UpdateGateTags.BLOCKING_CONFIRM).performClick()
        assertEquals(1, confirms)
        assertEquals(0, dismisses)
    }

    @Test
    fun `슬롯을 교체하면 기본 UI 대신 쓴다`() {
        rule.setContent {
            UpdateGate(
                optional, onConfirm = {}, onDismiss = {}, texts = texts,
                dialog = { Text("내 다이얼로그 ${it.title}") },
            ) { Text("본문") }
        }
        rule.onNodeWithText("내 다이얼로그 선택 제목").assertIsDisplayed()
        rule.onNodeWithTag(UpdateGateTags.DIALOG_CONFIRM).assertDoesNotExist()
    }

    @Test
    fun `기본 문구는 리소스에서 읽는다`() {
        rule.setContent { UpdateGate(blocking, onConfirm = {}, onDismiss = {}) { Text("본문") } }
        rule.onNodeWithText("Update required").assertIsDisplayed()
    }

    @Test
    fun `실패 화면의 기본 문구도 리소스에서 읽는다`() {
        val failed = ForceUpdateDecision.CheckFailed(UpdateException("x"), CheckFailureReason.INVALID_REQUIREMENT)
        rule.setContent { UpdateGate(UpdateUiModel.CheckFailed(failed), onConfirm = {}, onDismiss = {}) { Text("본문") } }
        rule.onNodeWithText("Try again").assertIsDisplayed()
    }
}
