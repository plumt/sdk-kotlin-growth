package net.plumt.growth.update

/**
 * 강제가 아닐 때 Play 에 새 버전이 있으면 어떤 방식으로 권할지 정한다. 교체하려면 구현해 [AppUpdater.create] 에 넘긴다.
 *
 * 구현은 부수효과가 없는 순수 함수여야 한다.
 */
public fun interface PlayUpdateStrategy {

    /** @return 시작할 방식. 이번에는 권하지 않으면 `null` */
    public fun choose(available: PlayUpdateAvailability.Available): PlayUpdateType?
}

/**
 * 기본 전략 — Play 의 우선순위·경과 일수로 고른다.
 *
 * - 즉시 허용 && (우선순위 >= [Config.immediateMinPriority] 또는 경과 일수 >= [Config.immediateMinStalenessDays]) 이면 즉시
 * - 그렇지 않고 유연 허용 && 우선순위 >= [Config.flexibleMinPriority] && 경과 일수 >= [Config.flexibleMinStalenessDays] 이면 유연
 * - 그 밖에는 권하지 않는다
 *
 * 경과 일수를 알 수 없으면(`null`) 임계값이 0 일 때만 충족한 것으로 본다.
 */
public class DefaultPlayUpdateStrategy @JvmOverloads constructor(
    private val config: Config = Config(),
) : PlayUpdateStrategy {

    /**
     * @property immediateMinPriority 즉시 업데이트로 올리는 우선순위 기준(0..5). 6 이상이면 우선순위로는 올리지 않는다
     * @property immediateMinStalenessDays 즉시 업데이트로 올리는 경과 일수. `null` 이면 경과 일수로는 올리지 않는다
     * @property flexibleMinPriority 유연 업데이트를 권하는 최소 우선순위
     * @property flexibleMinStalenessDays 유연 업데이트를 권하는 최소 경과 일수
     */
    public data class Config @JvmOverloads constructor(
        val immediateMinPriority: Int = 4,
        val immediateMinStalenessDays: Int? = null,
        val flexibleMinPriority: Int = 0,
        val flexibleMinStalenessDays: Int = 0,
    ) {
        init {
            require(immediateMinPriority >= 0) { "immediateMinPriority 는 0 이상이어야 합니다. 받은 값: $immediateMinPriority" }
            require(immediateMinStalenessDays == null || immediateMinStalenessDays >= 0) {
                "immediateMinStalenessDays 는 null 이거나 0 이상이어야 합니다. 받은 값: $immediateMinStalenessDays"
            }
            require(flexibleMinPriority >= 0) { "flexibleMinPriority 는 0 이상이어야 합니다. 받은 값: $flexibleMinPriority" }
            require(flexibleMinStalenessDays >= 0) { "flexibleMinStalenessDays 는 0 이상이어야 합니다. 받은 값: $flexibleMinStalenessDays" }
        }
    }

    override fun choose(available: PlayUpdateAvailability.Available): PlayUpdateType? {
        val staleness = available.stalenessDays
        val immediateByStaleness = config.immediateMinStalenessDays != null &&
            staleness != null && staleness >= config.immediateMinStalenessDays
        if (available.immediateAllowed && (available.priority >= config.immediateMinPriority || immediateByStaleness)) {
            return PlayUpdateType.IMMEDIATE
        }
        val flexibleByStaleness = (staleness ?: 0) >= config.flexibleMinStalenessDays &&
            (staleness != null || config.flexibleMinStalenessDays == 0)
        if (available.flexibleAllowed && available.priority >= config.flexibleMinPriority && flexibleByStaleness) {
            return PlayUpdateType.FLEXIBLE
        }
        return null
    }
}
