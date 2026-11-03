package net.plumt.growth.update

import kotlinx.coroutines.CancellationException

/**
 * [MinimumVersionSource] 의 요구를 설치된 버전에 적용해 강제·권장 업데이트 여부를 판정한다.
 *
 * 출처 조회가 실패해도 예외를 던지지 않고 [ForceUpdateDecision.CheckFailed] 로 알린다.
 * 실패 시 앱을 막을지(fail-closed) 통과시킬지(fail-open)는 호출부가 정한다 — 이 라이브러리는 정하지 않는다.
 * 코루틴 취소만 그대로 전파한다.
 */
public interface ForceUpdateChecker {

    public suspend fun check(): ForceUpdateDecision

    public companion object {
        /**
         * @param source 최소·권장 버전 출처
         * @param installed 설치된 버전. 기본값 없음 — `InstalledVersion.of(context)` 를 넘기거나 테스트 값을 넘긴다
         */
        @JvmStatic
        public fun create(source: MinimumVersionSource, installed: InstalledVersion): ForceUpdateChecker =
            DefaultForceUpdateChecker(source, installed)
    }
}

/** [ForceUpdateChecker.check] 의 결과. */
public sealed interface ForceUpdateDecision {

    /** 요구를 모두 만족한다(또는 출처에 요구가 없다). */
    public data object UpToDate : ForceUpdateDecision

    /** 최소 버전보다 낮다 — 업데이트해야 앱을 쓸 수 있다. */
    public data class Required(
        val installed: InstalledVersion,
        val minimumVersionName: String?,
        val minimumVersionCode: Long?,
    ) : ForceUpdateDecision

    /** 권장 버전보다 낮다 — 업데이트를 권하되 막지는 않는다. */
    public data class Recommended(
        val installed: InstalledVersion,
        val recommendedVersionName: String?,
        val recommendedVersionCode: Long?,
    ) : ForceUpdateDecision

    /** 요구를 얻거나 해석하지 못했다. 원인은 [cause] 다. */
    public data class CheckFailed(val cause: UpdateException) : ForceUpdateDecision
}

internal class DefaultForceUpdateChecker(
    private val source: MinimumVersionSource,
    private val installed: InstalledVersion,
) : ForceUpdateChecker {

    override suspend fun check(): ForceUpdateDecision {
        val requirement = try {
            source.fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ForceUpdateDecision.CheckFailed(UpdateException("최소 버전 출처를 읽지 못했습니다.", e))
        }
        return try {
            requirement.evaluate(installed)
        } catch (e: IllegalArgumentException) {
            ForceUpdateDecision.CheckFailed(UpdateException("버전을 비교하지 못했습니다.", e))
        }
    }
}
