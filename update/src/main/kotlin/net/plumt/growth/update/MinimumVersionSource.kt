package net.plumt.growth.update

/**
 * 최소·권장 버전의 원격 출처 포트. 앱이 Firebase Remote Config, 자체 서버, 정적 JSON 등으로 구현한다.
 * 이 라이브러리는 어떤 원격 SDK 에도 의존하지 않는다.
 *
 * ```kotlin
 * val source = MinimumVersionSource {
 *     VersionRequirement(minimumVersionName = remoteConfig.getString("min_version"))
 * }
 * ```
 *
 * 구현 규칙
 * - 아무 요구도 없으면 [VersionRequirement.NONE] 을 돌려준다(`null` 이 아니다)
 * - 출처에 닿지 못했으면 예외를 던진다. 어떤 예외든 [ForceUpdateChecker] 가 [UpdateException] 으로 감싸 [ForceUpdateDecision.CheckFailed] 로 알린다
 * - 코루틴 취소는 삼키지 않는다(취소 예외는 그대로 전파된다)
 * - 네트워크 호출이면 자체 타임아웃을 둔다. 라이브러리는 시간을 제한하지 않는다
 */
public fun interface MinimumVersionSource {
    public suspend fun fetch(): VersionRequirement
}
