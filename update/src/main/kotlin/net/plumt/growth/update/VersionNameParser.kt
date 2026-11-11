package net.plumt.growth.update

/**
 * 설치된 앱의 버전 이름(`versionName`)을 비교 가능한 [SemanticVersion] 으로 바꾸는 규칙.
 *
 * 앱의 버전 이름에는 빌드 변형 접미사(`1.4.0-prod`, `1.4.0-debug`)나 빌드 번호(`1.4.0 (45)`)가 붙는 일이 많다.
 * 이것을 SemVer 사전 릴리스로 읽으면 `1.4.0-prod < 1.4.0` 이 되어 최신 정식 빌드가 강제 업데이트에 막힌다.
 * 그래서 기본값 [NUMERIC_PREFIX] 는 앞쪽 숫자 구간만 읽고 나머지를 버린다.
 *
 * 해석하지 못하면 0 같은 대체값을 만들지 않고 [IllegalArgumentException] 을 던진다.
 * [ForceUpdateChecker.create]·[AppUpdater.create] 가 생성 시점에 설치 버전을 한 번 해석하므로, 규칙이 맞지 않으면
 * 강제 업데이트가 조용히 꺼지는 대신 개발 중에 바로 드러난다.
 *
 * 다른 형식(날짜 버전 등)을 쓰는 앱은 이 인터페이스를 구현해 넘긴다. 구현은 순수 함수여야 한다.
 */
public fun interface VersionNameParser {

    /** @throws IllegalArgumentException [versionName] 을 해석할 수 없을 때(받은 값을 메시지에 담는다) */
    public fun parse(versionName: String): SemanticVersion

    public companion object {
        /**
         * 기본 규칙 — 앞뒤 공백을 떼고, 맨 앞의 숫자 구간(1~4개)만 읽는다. 뒤에 붙은 `-prod`·`_beta`·` (45)`·`+build` 는 무시한다.
         *
         * `1.4.0-prod` → `1.4.0`, `1.4.0 (45)` → `1.4.0`, `2.1` → `2.1`.
         * 숫자로 시작하지 않거나(`v1.2`, `release`) 숫자 구간이 5개 이상이면(`2026.11.03.1.2`) [IllegalArgumentException] 이다.
         * 사전 릴리스도 버리므로 설치본 `1.4.0-beta.1` 은 `1.4.0` 으로 본다 — 사전 릴리스를 구분해야 하면 [STRICT] 를 쓴다.
         */
        @JvmField
        public val NUMERIC_PREFIX: VersionNameParser = VersionNameParser { name ->
            val match = NUMERIC_PREFIX_PATTERN.find(name.trim()) ?: throw IllegalArgumentException(
                "설치된 버전 이름에서 숫자 구간(1~4개)을 찾지 못했습니다. 받은 값: \"$name\" " +
                    "(예: 1.4.0, 1.4.0-prod). 다른 형식이면 VersionNameParser 를 구현해 넘기세요.",
            )
            SemanticVersion.parse(match.groupValues[1])
        }

        /** 이름 전체를 [SemanticVersion.parse] 로 읽는다. 접미사는 SemVer 사전 릴리스로 해석되어 정식 버전보다 낮다. */
        @JvmField
        public val STRICT: VersionNameParser = VersionNameParser { SemanticVersion.parse(it.trim()) }
    }
}

// 맨 앞 숫자 구간 1~4개. 다섯 번째 구간이 이어지면 맞지 않는다(잘라 읽지 않는다).
// ASCII 숫자만 — \d 는 Android 에서 유니코드 숫자까지 맞춰 JVM 과 결과가 갈린다
private val NUMERIC_PREFIX_PATTERN = Regex("""^([0-9]+(?:\.[0-9]+){0,3})(?![.]?[0-9])""")
