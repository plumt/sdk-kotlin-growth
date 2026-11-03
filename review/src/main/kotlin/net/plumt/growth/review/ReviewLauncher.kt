package net.plumt.growth.review

import android.app.Activity

/**
 * Play 인앱 리뷰 플로우를 실제로 실행하는 포트. 기본 구현은 [PlayReviewLauncher] 이고,
 * 다른 리뷰 수단(자체 다이얼로그 등)으로 바꾸려면 구현해서 [ReviewPrompt.create] 에 넘긴다.
 *
 * **표시 여부를 돌려주지 않는다.** Play 는 쿼터·내부 판단으로 다이얼로그를 조용히 건너뛸 수 있고,
 * 건너뛴 경우에도 플로우는 똑같이 "끝남" 으로 보고된다. 그래서 반환은 "플로우가 끝났다" 와 "실패했다" 둘뿐이다.
 */
public interface ReviewLauncher {

    /**
     * 리뷰 플로우를 시작해 끝날 때까지 기다린다. 메인 스레드에서 불러도 된다(내부에서 블로킹하지 않는다).
     *
     * @param activity 다이얼로그를 붙일 Activity. 종료 중이 아니어야 한다
     * @return 플로우가 끝나면 [ReviewLaunchResult.FlowFinished], Play 가 실패하면 [ReviewLaunchResult.Failed]
     */
    public suspend fun launch(activity: Activity): ReviewLaunchResult
}

/** [ReviewLauncher.launch] 의 결과. */
public sealed interface ReviewLaunchResult {

    /**
     * 플로우가 오류 없이 끝났다. **다이얼로그가 실제로 보였다는 뜻이 아니다** — Play 의 쿼터로 건너뛰었을 수 있다.
     */
    public data object FlowFinished : ReviewLaunchResult

    /** Play 서비스가 없거나 요청이 거부되는 등 플로우를 끝내지 못했다. 원인은 [cause] 다. */
    public data class Failed(val cause: ReviewException) : ReviewLaunchResult
}
