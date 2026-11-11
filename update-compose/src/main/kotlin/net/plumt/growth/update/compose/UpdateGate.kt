package net.plumt.growth.update.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** [UpdateGate] 의 `testTag` 모음 — UI 테스트에서 쓴다. */
public object UpdateGateTags {
    public const val DIALOG_CONFIRM: String = "growth_update_dialog_confirm"
    public const val DIALOG_LATER: String = "growth_update_dialog_later"
    public const val BLOCKING_SCREEN: String = "growth_update_blocking_screen"
    public const val BLOCKING_CONFIRM: String = "growth_update_blocking_confirm"
}

/** 다이얼로그 슬롯이 받는 값. 교체 구현은 이 값으로 자기 UI 를 그린다. */
public data class UpdateDialogSpec(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val dismissLabel: String,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)

/** 차단 화면 슬롯이 받는 값. 닫는 동작이 없다 — 업데이트(또는 다시 확인) 외의 탈출구를 만들지 않는다. */
public data class BlockingSpec(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val onConfirm: () -> Unit,
)

/**
 * 업데이트 안내를 [content] 위에 얹는 게이트.
 *
 * - [UpdateUiModel.Blocking]·[UpdateUiModel.CheckFailed] 이면 [content] 를 **그리지 않고** [blocking] 슬롯만 그린다.
 *   강제 업데이트 중 Play 즉시 업데이트 화면을 띄울 때도 그 아래는 차단 화면이라, Play 화면을 뒤로 가기로 닫아도 본문이 드러나지 않는다
 * - [UpdateUiModel.Optional]·[UpdateUiModel.ReadyToInstall] 이면 [content] 위에 [dialog] 슬롯을 띄운다
 * - [UpdateUiModel.Hidden] 이면 [content] 만 그린다
 *
 * 두 슬롯의 기본값은 Material 3 구현이고, 앱의 디자인 시스템으로 통째로 교체할 수 있다.
 * 차단 화면은 시스템 뒤로 가기를 막지 않는다(앱이 닫히는 것은 업데이트 우회가 아니다).
 *
 * @param onConfirm 확인 — [UpdateUiModel.Optional] 에서는 업데이트 시작, [UpdateUiModel.Blocking] 에서는 그 `action` 실행(Play 흐름 또는 스토어 열기),
 *   [UpdateUiModel.ReadyToInstall] 에서는 설치, [UpdateUiModel.CheckFailed] 에서는 다시 확인
 * @param onDismiss 다이얼로그를 거절했을 때(차단 화면에는 없다)
 */
@Composable
public fun UpdateGate(
    model: UpdateUiModel,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    texts: UpdateTexts = UpdateTexts.default(),
    dialog: @Composable (UpdateDialogSpec) -> Unit = { DefaultUpdateDialog(it) },
    blocking: @Composable (BlockingSpec) -> Unit = { DefaultBlockingScreen(it, modifier) },
    content: @Composable () -> Unit,
) {
    when (model) {
        is UpdateUiModel.Blocking -> blocking(
            BlockingSpec(texts.requiredTitle, texts.requiredMessage, texts.confirm, onConfirm),
        )
        is UpdateUiModel.CheckFailed -> blocking(
            BlockingSpec(texts.checkFailedTitle, texts.checkFailedMessage, texts.retry, onConfirm),
        )
        else -> {
            content()
            when (model) {
                is UpdateUiModel.Optional -> dialog(
                    UpdateDialogSpec(texts.optionalTitle, texts.optionalMessage, texts.confirm, texts.later, onConfirm, onDismiss),
                )
                UpdateUiModel.ReadyToInstall -> dialog(
                    UpdateDialogSpec(texts.readyTitle, texts.readyMessage, texts.install, texts.later, onConfirm, onDismiss),
                )
                else -> Unit
            }
        }
    }
}

/** 기본 다이얼로그 — Material 3 `AlertDialog`. */
@Composable
public fun DefaultUpdateDialog(spec: UpdateDialogSpec) {
    AlertDialog(
        onDismissRequest = spec.onDismiss,
        title = { Text(spec.title) },
        text = { Text(spec.message) },
        confirmButton = {
            TextButton(onClick = spec.onConfirm, modifier = Modifier.testTag(UpdateGateTags.DIALOG_CONFIRM)) { Text(spec.confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = spec.onDismiss, modifier = Modifier.testTag(UpdateGateTags.DIALOG_LATER)) { Text(spec.dismissLabel) }
        },
    )
}

/** 기본 차단 화면 — 제목, 설명, 업데이트 버튼만 있는 전체 화면. */
@Composable
public fun DefaultBlockingScreen(spec: BlockingSpec, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize().testTag(UpdateGateTags.BLOCKING_SCREEN), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(spec.title, style = MaterialTheme.typography.headlineSmall)
            Text(spec.message, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = spec.onConfirm, modifier = Modifier.testTag(UpdateGateTags.BLOCKING_CONFIRM)) { Text(spec.confirmLabel) }
        }
    }
}
