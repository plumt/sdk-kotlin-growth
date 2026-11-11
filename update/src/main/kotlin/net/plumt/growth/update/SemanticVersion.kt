package net.plumt.growth.update

/**
 * `1.2`, `1.2.3`, `1.2.3.4`, `1.2.3-beta.1`, `1.2.3+build5` 형태의 버전 이름.
 *
 * 비교는 [SemVer 2.0](https://semver.org/lang/ko/) 우선순위를 따른다.
 * - 숫자 구간은 숫자로 비교하고 모자란 구간은 0 으로 본다(`1.2` == `1.2.0`)
 * - 사전 릴리스(`-beta.1`)가 붙은 버전은 같은 숫자의 정식 버전보다 낮다
 * - 빌드 메타데이터(`+build5`)는 비교에서 무시한다
 *
 * 숫자 구간은 1~4개, 각 구간은 `Long` 범위의 십진수여야 한다. 사전 릴리스의 숫자 식별자는 SemVer 대로 선행 0 을 허용하지 않는다
 * (`1.0.0-01` 거부 — `01` 과 `1` 을 같은 값으로 볼지 모호해 `equals`·`hashCode` 가 어긋난다).
 * 해석하지 못하는 문자열은 0 으로 바꾸지 않고 [parse] 가 [IllegalArgumentException] 을 던진다.
 *
 * 설치된 앱의 버전 이름(`1.4.0-prod`, `1.4.0 (45)` 처럼 빌드 접미사가 붙는 값)은 이 클래스로 직접 해석하지 말고
 * [VersionNameParser] 를 쓴다.
 */
public class SemanticVersion private constructor(
    private val numbers: List<Long>,
    private val preRelease: List<String>,
    private val text: String,
) : Comparable<SemanticVersion> {

    /** 숫자 구간(끝의 0 은 떼지 않은 원본 개수). */
    public val numericParts: List<Long> get() = numbers

    /** 사전 릴리스 표시가 붙은 버전인지. */
    public val isPreRelease: Boolean get() = preRelease.isNotEmpty()

    override fun compareTo(other: SemanticVersion): Int {
        val size = maxOf(numbers.size, other.numbers.size)
        for (i in 0 until size) {
            val c = (numbers.getOrElse(i) { 0L }).compareTo(other.numbers.getOrElse(i) { 0L })
            if (c != 0) return c
        }
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            return when {
                preRelease.isEmpty() && other.preRelease.isEmpty() -> 0
                preRelease.isEmpty() -> 1
                else -> -1
            }
        }
        for (i in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val c = compareIdentifier(preRelease[i], other.preRelease[i])
            if (c != 0) return c
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    override fun equals(other: Any?): Boolean = other is SemanticVersion && compareTo(other) == 0

    override fun hashCode(): Int = 31 * numbers.dropLastWhile { it == 0L }.hashCode() + preRelease.hashCode()

    /** 파싱에 쓴 원본 문자열. */
    override fun toString(): String = text

    private fun compareIdentifier(a: String, b: String): Int {
        val an = a.toLongOrNull()
        val bn = b.toLongOrNull()
        return when {
            an != null && bn != null -> an.compareTo(bn)
            an != null -> -1 // 숫자 식별자는 문자 식별자보다 낮다
            bn != null -> 1
            else -> a.compareTo(b)
        }
    }

    public companion object {
        // 사전 릴리스 식별자: 0, 선행 0 없는 수, 또는 문자가 하나 이상 섞인 영숫자.
        // 숫자는 [0-9] 로 적는다 — \d 는 Android 에서 '٣'·'３' 같은 유니코드 숫자까지 맞춰 JVM 과 결과가 갈린다(SemVer 는 ASCII 만)
        private const val ID = """(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"""
        private val PATTERN = Regex("""^([0-9]+(?:\.[0-9]+){0,3})(?:-($ID(?:\.$ID)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$""")

        /** @throws IllegalArgumentException 형식이 맞지 않거나 숫자가 `Long` 범위를 넘을 때 */
        @JvmStatic
        public fun parse(text: String): SemanticVersion =
            parseOrNull(text) ?: throw IllegalArgumentException(
                "버전 이름을 해석할 수 없습니다. 받은 값: \"$text\" (예: 1.2.3, 1.2.3-beta.1)",
            )

        /** [parse] 와 같되 해석하지 못하면 `null` 이다. 호출부가 `null` 을 오류로 다뤄야 한다. */
        @JvmStatic
        public fun parseOrNull(text: String): SemanticVersion? {
            val match = PATTERN.matchEntire(text) ?: return null
            val numbers = match.groupValues[1].split('.').map { it.toLongOrNull() ?: return null }
            val pre = match.groupValues[2].takeIf { it.isNotEmpty() }?.split('.').orEmpty()
            return SemanticVersion(numbers, pre, text)
        }
    }
}
