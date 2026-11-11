package net.plumt.growth.update

/**
 * 원격 출처가 알려주는 버전 요구.
 *
 * - 최소 버전(`minimum*`): 이보다 낮으면 **반드시** 업데이트해야 한다(강제)
 * - 권장 버전(`recommended*`): 이보다 낮으면 업데이트를 **권한다**(선택)
 *
 * 값 규칙
 * - `null`, 빈 문자열, 공백뿐인 문자열은 모두 **설정 없음**이다. Remote Config 의 `getString` 은 없는 키에 `""` 를 주므로
 *   키 하나를 아직 만들지 않았어도 다른 필드의 요구는 그대로 살아 있다. 앞뒤 공백은 떼어 낸다
 * - 그 밖의 이름이 [SemanticVersion] 형식이 아니면(`v2.0.0`, `2.0.0.0.1` 등) 생성 시 [IllegalArgumentException] 이다 —
 *   설정 오류는 "요구 없음" 으로 바꾸지 않는다. [ForceUpdateChecker] 는 이것을
 *   [ForceUpdateDecision.CheckFailed] ([CheckFailureReason.INVALID_REQUIREMENT]) 로 알린다
 * - 이름과 코드를 둘 다 주면 하나라도 모자라면 해당한다
 *
 * @property minimumVersionName 정규화된 최소 버전 이름(설정 없음이면 `null`)
 * @property recommendedVersionName 정규화된 권장 버전 이름(설정 없음이면 `null`)
 */
public class VersionRequirement @JvmOverloads constructor(
    minimumVersionName: String? = null,
    public val minimumVersionCode: Long? = null,
    recommendedVersionName: String? = null,
    public val recommendedVersionCode: Long? = null,
) {
    public val minimumVersionName: String? = minimumVersionName.normalized()
    public val recommendedVersionName: String? = recommendedVersionName.normalized()

    private val minimumName: SemanticVersion? = parseField("minimumVersionName", this.minimumVersionName)
    private val recommendedName: SemanticVersion? = parseField("recommendedVersionName", this.recommendedVersionName)

    init {
        require(minimumVersionCode == null || minimumVersionCode >= 0) { "minimumVersionCode 는 0 이상이어야 합니다. 받은 값: $minimumVersionCode" }
        require(recommendedVersionCode == null || recommendedVersionCode >= 0) { "recommendedVersionCode 는 0 이상이어야 합니다. 받은 값: $recommendedVersionCode" }
    }

    /** 요구가 하나도 없는지. */
    public val isEmpty: Boolean
        get() = minimumName == null && minimumVersionCode == null && recommendedName == null && recommendedVersionCode == null

    /**
     * 설치된 버전에 이 요구를 적용한 결과. 출처 조회 실패는 여기서 다루지 않는다([ForceUpdateChecker] 참고).
     *
     * 우선순위는 [ForceUpdateDecision.Required] > [ForceUpdateDecision.Recommended] > [ForceUpdateDecision.UpToDate] 이다.
     * 이름 요구가 없으면 설치 버전 이름은 해석하지 않는다.
     *
     * @param parser 설치 버전 이름을 해석하는 규칙. 기본은 접미사를 무시하는 [VersionNameParser.NUMERIC_PREFIX]
     * @throws IllegalArgumentException 이름 요구가 있는데 [parser] 가 [installed] 의 이름을 해석하지 못할 때
     */
    @JvmOverloads
    public fun evaluate(installed: InstalledVersion, parser: VersionNameParser = VersionNameParser.NUMERIC_PREFIX): ForceUpdateDecision {
        val installedName = if (minimumName != null || recommendedName != null) parser.parse(installed.versionName) else null
        if (isBelow(installedName, installed.versionCode, minimumName, minimumVersionCode)) {
            return ForceUpdateDecision.Required(installed, minimumVersionName, minimumVersionCode)
        }
        if (isBelow(installedName, installed.versionCode, recommendedName, recommendedVersionCode)) {
            return ForceUpdateDecision.Recommended(installed, recommendedVersionName, recommendedVersionCode)
        }
        return ForceUpdateDecision.UpToDate
    }

    private fun isBelow(name: SemanticVersion?, code: Long, targetName: SemanticVersion?, targetCode: Long?): Boolean {
        val belowName = targetName != null && name != null && name < targetName
        val belowCode = targetCode != null && code < targetCode
        return belowName || belowCode
    }

    override fun equals(other: Any?): Boolean = other is VersionRequirement &&
        minimumVersionName == other.minimumVersionName && minimumVersionCode == other.minimumVersionCode &&
        recommendedVersionName == other.recommendedVersionName && recommendedVersionCode == other.recommendedVersionCode

    override fun hashCode(): Int =
        listOf(minimumVersionName, minimumVersionCode, recommendedVersionName, recommendedVersionCode).hashCode()

    override fun toString(): String =
        "VersionRequirement(minimumVersionName=$minimumVersionName, minimumVersionCode=$minimumVersionCode, " +
            "recommendedVersionName=$recommendedVersionName, recommendedVersionCode=$recommendedVersionCode)"

    public companion object {
        /** 요구가 없다. */
        @JvmField
        public val NONE: VersionRequirement = VersionRequirement()

        private fun String?.normalized(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

        private fun parseField(field: String, value: String?): SemanticVersion? = value?.let {
            SemanticVersion.parseOrNull(it) ?: throw IllegalArgumentException(
                "$field 를 버전으로 해석할 수 없습니다. 받은 값: \"$it\" (예: 1.2.3, 1.2.3-beta.1. 설정하지 않으려면 빈 값)",
            )
        }
    }
}

/** 설치된 앱의 버전. */
public data class InstalledVersion(
    val versionName: String,
    val versionCode: Long,
) {
    init {
        require(versionName.isNotBlank()) { "versionName 은 비어 있을 수 없습니다." }
        require(versionCode >= 0) { "versionCode 는 0 이상이어야 합니다. 받은 값: $versionCode" }
    }

    public companion object {
        /**
         * 이 앱의 패키지 정보에서 읽는다.
         *
         * @throws UpdateException 패키지 정보를 얻지 못하거나 `versionName` 이 없을 때
         */
        @JvmStatic
        public fun of(context: android.content.Context): InstalledVersion = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val name = info.versionName ?: throw UpdateException("설치된 패키지에 versionName 이 없습니다.")
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            InstalledVersion(name, code)
        } catch (e: UpdateException) {
            throw e
        } catch (e: Exception) {
            throw UpdateException("설치된 앱의 버전을 읽지 못했습니다.", e)
        }
    }
}
