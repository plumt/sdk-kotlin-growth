package net.plumt.growth.update.compose

import net.plumt.growth.update.UpdateAction
import net.plumt.growth.update.UpdateCheckResult
import net.plumt.growth.update.PlayUpdateType

/**
 * 업데이트 확인 결과를 화면이 무엇을 보여줄지로 바꾼 값. 순수 데이터라 유닛 테스트가 가능하다.
 * [UpdateGate] 를 쓰지 않고 직접 화면을 만들 때도 이 값을 그대로 쓸 수 있다.
 */
public sealed interface UpdateUiModel {

    /** 보여줄 것이 없다. */
    public data object Hidden : UpdateUiModel

    /** 선택 업데이트를 권하는 다이얼로그([action] 은 Play 로 시작할 동작). 거절할 수 있다. */
    public data class Optional(val action: UpdateAction.StartPlay) : UpdateUiModel

    /** 내려받은 업데이트를 설치(앱 재시작)하라는 다이얼로그. 거절할 수 있다. */
    public data object ReadyToInstall : UpdateUiModel

    /** 앱 사용을 막는 필수 업데이트 화면. 닫을 수 없다. */
    public data class Blocking(val action: UpdateAction.BlockUntilStoreUpdate) : UpdateUiModel

    public companion object {
        /**
         * @param result [net.plumt.growth.update.AppUpdater.check] 의 결과
         *
         * 규칙
         * - [UpdateAction.BlockUntilStoreUpdate] → [Blocking]
         * - [UpdateAction.InstallDownloaded] → [ReadyToInstall]
         * - 유연 방식 [UpdateAction.StartPlay] → [Optional]
         * - 즉시 방식 [UpdateAction.StartPlay]·[UpdateAction.ResumePlayImmediate] → [Hidden] (Play 가 자체 전체 화면을 띄운다. 앱이 바로 시작한다)
         * - [UpdateAction.None] → [Hidden]
         */
        @JvmStatic
        public fun from(result: UpdateCheckResult): UpdateUiModel = from(result.action)

        /** [from] (UpdateCheckResult) 와 같되 동작만 받는다. */
        @JvmStatic
        public fun from(action: UpdateAction): UpdateUiModel = when (action) {
            is UpdateAction.BlockUntilStoreUpdate -> Blocking(action)
            UpdateAction.InstallDownloaded -> ReadyToInstall
            is UpdateAction.StartPlay -> if (action.type == PlayUpdateType.FLEXIBLE) Optional(action) else Hidden
            UpdateAction.ResumePlayImmediate, UpdateAction.None -> Hidden
        }
    }
}
