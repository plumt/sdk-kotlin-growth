package net.plumt.growth.update

/** 앱이 다음에 해야 할 일. [UpdateActionResolver] 가 강제 판정과 Play 상태를 합쳐 정한다. */
public sealed interface UpdateAction {

    /** 할 일이 없다. */
    public data object None : UpdateAction

    /** Play 흐름을 [type] 방식으로 시작한다([AppUpdater.start]). */
    public data class StartPlay(val type: PlayUpdateType) : UpdateAction

    /** 진행 중이던 즉시 업데이트를 이어서 시작한다([AppUpdater.start]). */
    public data object ResumePlayImmediate : UpdateAction

    /** 내려받은 유연 업데이트를 설치한다([AppUpdater.start]). 앱이 재시작된다. */
    public data object InstallDownloaded : UpdateAction

    /**
     * 업데이트가 필수인데 Play 인앱 흐름으로 처리할 수 없다 — 앱을 막고 스토어로 보내야 한다
     * ([PlayStoreLink.intent]). 업데이트가 이 기기에 아직 배포되지 않았을 수도 있다.
     */
    public data class BlockUntilStoreUpdate(val required: ForceUpdateDecision.Required) : UpdateAction
}

/**
 * 강제·권장 판정([ForceUpdateDecision])과 Play 상태([PlayUpdateAvailability])를 하나의 [UpdateAction] 으로 합치는 순수 함수.
 *
 * 위에서부터 먼저 맞는 줄이 이긴다.
 *
 * | 순서 | 조건 | 동작 |
 * |---|---|---|
 * | 1 | Play 즉시 업데이트 진행 중 | [UpdateAction.ResumePlayImmediate] |
 * | 2 | 강제 + Play 에 새 버전 + 즉시 허용 | `StartPlay(IMMEDIATE)` |
 * | 3 | Play 가 내려받은 업데이트 보유 | [UpdateAction.InstallDownloaded] |
 * | 4 | 강제 (그 밖의 모든 Play 상태) | [UpdateAction.BlockUntilStoreUpdate] |
 * | 5 | Play 내려받는 중 | [UpdateAction.None] |
 * | 6 | Play 에 새 버전 | 전략([PlayUpdateStrategy]) 선택, 없으면 권장 + 유연 허용 시 `StartPlay(FLEXIBLE)` |
 * | 7 | 그 밖 | [UpdateAction.None] |
 *
 * 출처 조회 실패([ForceUpdateDecision.CheckFailed])는 강제도 권장도 아닌 것으로 본다(fail-open) —
 * 실패 때 막고 싶으면 호출부가 [ForceUpdateDecision] 을 직접 보고 처리한다.
 */
public object UpdateActionResolver {

    public fun resolve(
        force: ForceUpdateDecision,
        play: PlayUpdateAvailability,
        strategy: PlayUpdateStrategy = DefaultPlayUpdateStrategy(),
    ): UpdateAction {
        val required = force as? ForceUpdateDecision.Required
        if (play is PlayUpdateAvailability.ImmediateInProgress) return UpdateAction.ResumePlayImmediate
        if (required != null && play is PlayUpdateAvailability.Available && play.immediateAllowed) {
            return UpdateAction.StartPlay(PlayUpdateType.IMMEDIATE)
        }
        if (play is PlayUpdateAvailability.Downloaded) return UpdateAction.InstallDownloaded
        if (required != null) return UpdateAction.BlockUntilStoreUpdate(required)
        if (play is PlayUpdateAvailability.Available) {
            strategy.choose(play)?.let { return UpdateAction.StartPlay(it) }
            if (force is ForceUpdateDecision.Recommended && play.flexibleAllowed) {
                return UpdateAction.StartPlay(PlayUpdateType.FLEXIBLE)
            }
        }
        return UpdateAction.None
    }
}
