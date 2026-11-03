package net.plumt.growth.update.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * 안내 화면의 문구. 기본값은 라이브러리의 문자열 리소스(영어, 한국어 번역 포함)이고, 앱이 자기 문구로 바꿔 넘길 수 있다.
 */
public data class UpdateTexts(
    val optionalTitle: String,
    val optionalMessage: String,
    val readyTitle: String,
    val readyMessage: String,
    val requiredTitle: String,
    val requiredMessage: String,
    val confirm: String,
    val install: String,
    val later: String,
) {
    public companion object {
        /** 라이브러리 기본 문구(현재 로캘). */
        @Composable
        public fun default(): UpdateTexts = UpdateTexts(
            optionalTitle = stringResource(R.string.growth_update_optional_title),
            optionalMessage = stringResource(R.string.growth_update_optional_message),
            readyTitle = stringResource(R.string.growth_update_ready_title),
            readyMessage = stringResource(R.string.growth_update_ready_message),
            requiredTitle = stringResource(R.string.growth_update_required_title),
            requiredMessage = stringResource(R.string.growth_update_required_message),
            confirm = stringResource(R.string.growth_update_confirm),
            install = stringResource(R.string.growth_update_install),
            later = stringResource(R.string.growth_update_later),
        )
    }
}
