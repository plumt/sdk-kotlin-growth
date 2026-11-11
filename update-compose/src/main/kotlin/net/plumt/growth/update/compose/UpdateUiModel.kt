package net.plumt.growth.update.compose

import net.plumt.growth.update.ForceUpdateDecision
import net.plumt.growth.update.PlayUpdateType
import net.plumt.growth.update.UpdateAction
import net.plumt.growth.update.UpdateCheckResult

/**
 * 업데이트 확인 결과를 화면이 무엇을 보여줄지로 바꾼 값. 순수 데이터라 유닛 테스트가 가능하다.
 * [UpdateGate] 를 쓰지 않고 직접 화면을 만들 때도 이 값을 그대로 쓸 수 있다.
 */
public sealed interface UpdateUiModel {

    /** 보여줄 것이 없다. */
    public data object Hidden : UpdateUiModel

    /** 선택 업데이트를 권하는 다이얼로그([action] 은 Play 로 시작할 동작). 거절할 수 있다. */
    public data class Optional(val action: UpdateAction.StartPlay) : UpdateUiModel

    /** 내려받은 업데이트를 설치(앱 재시작)하라는 다이얼로그. 거절할 수 있다(강제가 아닐 때만 나온다). */
    public data object ReadyToInstall : UpdateUiModel

    /**
     * 앱 사용을 막는 필수 업데이트 화면. 닫을 수 없다.
     *
     * 확인 버튼은 [action] 을 실행한다 — Play 동작(`StartPlay(IMMEDIATE)`·`ResumePlayImmediate`·`InstallDownloaded`)이면
     * `AppUpdater.start`, [UpdateAction.BlockUntilStoreUpdate] 면 `PlayStoreLink.open`. Play 흐름이 취소·실패해도
     * 이 모델은 바뀌지 않으므로 본문은 계속 그려지지 않는다.
     *
     * @property required 강제 판정
     * @property action 확인 버튼이 실행할 동작
     */
    public data class Blocking(val required: ForceUpdateDecision.Required, val action: UpdateAction) : UpdateUiModel {
        /** 확인 버튼이 Play 흐름(`AppUpdater.start`)이 아니라 스토어 링크를 열어야 하는지. */
        public val opensStore: Boolean get() = action is UpdateAction.BlockUntilStoreUpdate
    }

    /** 원격 판정이 실패했고 실패 정책이 막기로 했다. 확인 버튼은 다시 확인(`AppUpdater.check`)한다. 닫을 수 없다. */
    public data class CheckFailed(val failure: ForceUpdateDecision.CheckFailed) : UpdateUiModel

    public companion object {
        /**
         * @param result [net.plumt.growth.update.AppUpdater.check]·`refresh` 의 결과
         *
         * 규칙(위에서부터)
         * - 강제 판정([ForceUpdateDecision.Required]) → **Play 동작이 무엇이든** [Blocking]. Play 즉시 업데이트 화면은 뒤로 가기로
         *   취소되고 내려받은 업데이트는 미룰 수 있으므로, 강제일 때 본문을 여는 경로를 두지 않는다
         * - [UpdateAction.BlockUntilCheckSucceeds] → [CheckFailed]
         * - [UpdateAction.InstallDownloaded] → [ReadyToInstall]
         * - 유연 방식 [UpdateAction.StartPlay] → [Optional]
         * - 즉시 방식 [UpdateAction.StartPlay]·[UpdateAction.ResumePlayImmediate] → [Hidden] (강제가 아니므로 Play 가 자체 화면을 띄운다)
         * - [UpdateAction.None] → [Hidden]
         */
        @JvmStatic
        public fun from(result: UpdateCheckResult): UpdateUiModel {
            val action = result.action
            val force = result.force
            if (force is ForceUpdateDecision.Required) return Blocking(force, action)
            return when (action) {
                is UpdateAction.BlockUntilCheckSucceeds -> CheckFailed(action.failure)
                UpdateAction.InstallDownloaded -> ReadyToInstall
                is UpdateAction.StartPlay -> if (action.type == PlayUpdateType.FLEXIBLE) Optional(action) else Hidden
                // 강제가 아닌데 차단 동작이 들어오는 것은 결정표를 거치지 않은 결과다 — 안전한 쪽(차단)으로 그린다
                is UpdateAction.BlockUntilStoreUpdate -> Blocking(action.required, action)
                UpdateAction.ResumePlayImmediate, UpdateAction.None -> Hidden
            }
        }
    }
}
