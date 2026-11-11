package net.plumt.growth.update

import kotlinx.coroutines.CancellationException

/**
 * [MinimumVersionSource] 의 요구를 설치된 버전에 적용해 강제·권장 업데이트 여부를 판정한다.
 *
 * 출처 조회가 실패해도 예외를 던지지 않고 [ForceUpdateDecision.CheckFailed] 로 알린다. 실패의 종류는
 * [CheckFailureReason] 으로 나뉜다 — 네트워크 같은 일시 실패([CheckFailureReason.SOURCE_UNAVAILABLE])와
 * 원격 값 자체가 잘못된 설정 오류([CheckFailureReason.INVALID_REQUIREMENT])를 구분해 로그·알림을 다르게 할 수 있다.
 * 실패 시 앱을 막을지(fail-closed) 통과시킬지(fail-open)는 [CheckFailurePolicy] 로 정한다([AppUpdater.create]).
 * 코루틴 취소만 그대로 전파한다.
 */
public interface ForceUpdateChecker {

    public suspend fun check(): ForceUpdateDecision

    public companion object {
        /**
         * @param source 최소·권장 버전 출처
         * @param installed 설치된 버전. 기본값 없음 — `InstalledVersion.of(context)` 를 넘기거나 테스트 값을 넘긴다
         * @param versionNameParser 설치 버전 이름을 해석하는 규칙. 기본은 빌드 접미사를 무시하는 [VersionNameParser.NUMERIC_PREFIX]
         * @throws IllegalArgumentException [versionNameParser] 가 [installed] 의 이름을 해석하지 못할 때 — 강제 업데이트가
         *   조용히 꺼지지 않도록 생성 시점에 드러낸다
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            source: MinimumVersionSource,
            installed: InstalledVersion,
            versionNameParser: VersionNameParser = VersionNameParser.NUMERIC_PREFIX,
        ): ForceUpdateChecker {
            versionNameParser.parse(installed.versionName)
            return DefaultForceUpdateChecker(source, installed, versionNameParser)
        }
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

    /** 요구를 얻거나 해석하지 못했다. 종류는 [reason], 원인은 [cause] 다. */
    public data class CheckFailed(val cause: UpdateException, val reason: CheckFailureReason) : ForceUpdateDecision
}

/** [ForceUpdateDecision.CheckFailed] 의 종류. */
public enum class CheckFailureReason {
    /** 출처에 닿지 못했다(네트워크·서버·SDK 오류 등). 대개 일시적이다. */
    SOURCE_UNAVAILABLE,

    /**
     * 출처의 값이 잘못됐다 — 버전 이름 형식 오류 등. [MinimumVersionSource] 가 [IllegalArgumentException] 을 던지면 이것이다.
     * 설정 실수이므로 다시 시도해도 낫지 않는다. 앱이 오류 보고로 알리기를 권한다.
     */
    INVALID_REQUIREMENT,

    /** 설치 버전 이름을 [VersionNameParser] 가 해석하지 못했다(생성 시 검사를 통과한 뒤 규칙이 다르게 답한 경우). */
    INVALID_INSTALLED_VERSION,
}

internal class DefaultForceUpdateChecker(
    private val source: MinimumVersionSource,
    private val installed: InstalledVersion,
    private val parser: VersionNameParser,
) : ForceUpdateChecker {

    override suspend fun check(): ForceUpdateDecision {
        val requirement = try {
            source.fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            return ForceUpdateDecision.CheckFailed(
                UpdateException("최소 버전 출처의 값이 잘못됐습니다: ${e.message}", e),
                CheckFailureReason.INVALID_REQUIREMENT,
            )
        } catch (e: Exception) {
            return ForceUpdateDecision.CheckFailed(
                UpdateException("최소 버전 출처를 읽지 못했습니다.", e),
                CheckFailureReason.SOURCE_UNAVAILABLE,
            )
        }
        return try {
            requirement.evaluate(installed, parser)
        } catch (e: IllegalArgumentException) {
            ForceUpdateDecision.CheckFailed(
                UpdateException("설치된 버전 이름을 해석하지 못했습니다: ${e.message}", e),
                CheckFailureReason.INVALID_INSTALLED_VERSION,
            )
        }
    }
}
