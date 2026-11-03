package net.plumt.growth.review

/**
 * 리뷰 카운터 저장소 포트. 기본 구현은 [SharedPreferencesReviewStore] 이고, DataStore·서버 등으로 바꾸려면 이 인터페이스를 구현한다.
 *
 * 구현 규칙
 * - 한 [ReviewPrompt] 안에서는 호출이 직렬화되어 들어온다. 프로세스가 여럿이면 구현이 책임진다
 * - [load] 는 저장된 것이 없으면 `ReviewState()` 를 돌려준다. 읽기에 실패하면 예외를 던진다(빈 값으로 가리지 않는다)
 */
public interface ReviewStore {
    public suspend fun load(): ReviewState
    public suspend fun save(state: ReviewState)
}

/** 메모리에만 두는 저장소. 테스트·일회성 용도다. 프로세스가 끝나면 사라진다. */
public class InMemoryReviewStore(initial: ReviewState = ReviewState()) : ReviewStore {
    @Volatile
    private var state: ReviewState = initial

    override suspend fun load(): ReviewState = state

    override suspend fun save(state: ReviewState) {
        this.state = state
    }
}
