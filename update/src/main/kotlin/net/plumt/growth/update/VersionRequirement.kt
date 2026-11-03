package net.plumt.growth.update

/**
 * 원격 출처가 알려주는 버전 요구. 값이 없는 필드는 `null` 이다.
 *
 * - 최소 버전(`minimum*`): 이보다 낮으면 **반드시** 업데이트해야 한다(강제)
 * - 권장 버전(`recommended*`): 이보다 낮으면 업데이트를 **권한다**(선택)
 *
 * 이름과 코드를 둘 다 주면 하나라도 모자라면 해당한다. 형식이 잘못된 값은 생성 시 [IllegalArgumentException] 이다.
 */
public data class VersionRequirement @JvmOverloads constructor(
    val minimumVersionName: String? = null,
    val minimumVersionCode: Long? = null,
    val recommendedVersionName: String? = null,
    val recommendedVersionCode: Long? = null,
) {
    init {
        minimumVersionName?.let { SemanticVersion.parse(it) }
        recommendedVersionName?.let { SemanticVersion.parse(it) }
        require(minimumVersionCode == null || minimumVersionCode >= 0) { "minimumVersionCode 는 0 이상이어야 합니다. 받은 값: $minimumVersionCode" }
        require(recommendedVersionCode == null || recommendedVersionCode >= 0) { "recommendedVersionCode 는 0 이상이어야 합니다. 받은 값: $recommendedVersionCode" }
    }

    /**
     * 설치된 버전에 이 요구를 적용한 결과. 출처 조회 실패는 여기서 다루지 않는다([ForceUpdateChecker] 참고).
     *
     * 우선순위는 [ForceUpdateDecision.Required] > [ForceUpdateDecision.Recommended] > [ForceUpdateDecision.UpToDate] 이다.
     *
     * @throws IllegalArgumentException [installed] 의 버전 이름을 해석할 수 없을 때
     */
    public fun evaluate(installed: InstalledVersion): ForceUpdateDecision {
        val installedName = SemanticVersion.parse(installed.versionName)
        if (isBelow(installedName, installed.versionCode, minimumVersionName, minimumVersionCode)) {
            return ForceUpdateDecision.Required(installed, minimumVersionName, minimumVersionCode)
        }
        if (isBelow(installedName, installed.versionCode, recommendedVersionName, recommendedVersionCode)) {
            return ForceUpdateDecision.Recommended(installed, recommendedVersionName, recommendedVersionCode)
        }
        return ForceUpdateDecision.UpToDate
    }

    private fun isBelow(name: SemanticVersion, code: Long, targetName: String?, targetCode: Long?): Boolean {
        val belowName = targetName != null && name < SemanticVersion.parse(targetName)
        val belowCode = targetCode != null && code < targetCode
        return belowName || belowCode
    }

    public companion object {
        /** 요구가 없다. */
        @JvmField
        public val NONE: VersionRequirement = VersionRequirement()
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
