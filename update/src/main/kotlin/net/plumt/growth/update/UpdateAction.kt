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

    /**
     * 원격 판정이 실패했고 [CheckFailurePolicy] 가 막기로 했다 — 다시 확인([AppUpdater.check])할 때까지 앱을 막는다.
     * 기본 정책([CheckFailurePolicy.FAIL_OPEN])에서는 나오지 않는다.
     */
    public data class BlockUntilCheckSucceeds(val failure: ForceUpdateDecision.CheckFailed) : UpdateAction
}

/**
 * 강제·권장 판정([ForceUpdateDecision])과 Play 상태([PlayUpdateAvailability])를 하나의 [UpdateAction] 으로 합치는 순수 함수.
 *
 * 위에서부터 먼저 맞는 줄이 이긴다.
 *
 * | 순서 | 조건 | 동작 |
 * |---|---|---|
 * | 0 | 원격 판정 실패 + [CheckFailurePolicy] 가 막음 | [UpdateAction.BlockUntilCheckSucceeds] |
 * | 1 | Play 즉시 업데이트 진행 중 | [UpdateAction.ResumePlayImmediate] |
 * | 2 | 강제 + Play 에 새 버전 + 즉시 허용 | `StartPlay(IMMEDIATE)` |
 * | 3 | Play 가 내려받은 업데이트 보유 | [UpdateAction.InstallDownloaded] |
 * | 4 | 강제 (그 밖의 모든 Play 상태) | [UpdateAction.BlockUntilStoreUpdate] |
 * | 5 | Play 내려받는 중 | [UpdateAction.None] |
 * | 6 | Play 에 새 버전 | 전략([PlayUpdateStrategy]) 선택, 없으면 권장 + 유연 허용 시 `StartPlay(FLEXIBLE)` |
 * | 7 | 그 밖 | [UpdateAction.None] |
 *
 * **강제([ForceUpdateDecision.Required])일 때는 1~3번 줄처럼 Play 동작이 나와도 앱 본문을 열면 안 된다.** Play 즉시 업데이트
 * 화면은 뒤로 가기로 취소되고, 내려받은 업데이트는 설치를 미룰 수 있다. 화면은 [UpdateCheckResult.isUpdateRequired] 로
 * 차단을 유지하고 그 위에서 Play 동작을 실행한다(update-compose 의 `UpdateUiModel` 이 그렇게 한다).
 *
 * 출처 조회 실패([ForceUpdateDecision.CheckFailed])는 [onCheckFailure] 가 막지 않으면 강제도 권장도 아닌 것으로 본다(fail-open).
 */
public object UpdateActionResolver {

    @JvmStatic
    @JvmOverloads
    public fun resolve(
        force: ForceUpdateDecision,
        play: PlayUpdateAvailability,
        strategy: PlayUpdateStrategy = DefaultPlayUpdateStrategy(),
        onCheckFailure: CheckFailurePolicy = CheckFailurePolicy.FAIL_OPEN,
    ): UpdateAction {
        if (force is ForceUpdateDecision.CheckFailed && onCheckFailure.shouldBlock(force)) {
            return UpdateAction.BlockUntilCheckSucceeds(force)
        }
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
