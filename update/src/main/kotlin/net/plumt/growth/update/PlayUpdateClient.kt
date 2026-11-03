package net.plumt.growth.update

import android.app.Activity
import android.content.Context
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import kotlinx.coroutines.flow.Flow
import net.plumt.growth.update.internal.DefaultPlayUpdateClient

/**
 * Google Play In-App Updates 를 감싼 포트. 기본 구현은 [create] 가 돌려주고, 다른 배포 채널을 쓰는 앱은 직접 구현해 바꾼다.
 *
 * 모든 `suspend` 함수는 메인 스레드에서 불러도 된다. 실패는 예외가 아니라 결과 값으로 돌려준다(취소 제외).
 * Play 스토어로 설치되지 않은 빌드(사이드로드·에뮬레이터)에서는 [availability] 가 [PlayUpdateAvailability.Failed] 이다.
 */
public interface PlayUpdateClient {

    /** Play 에 업데이트 상태를 묻는다. */
    public suspend fun availability(): PlayUpdateAvailability

    /**
     * 업데이트 흐름을 시작한다. 시작 직전에 최신 정보를 다시 묻는다.
     * 지금 허용되지 않는 방식이면 [PlayUpdateResult.Failed] 이다.
     *
     * 흐름이 끝날 때까지(사용자가 수락·취소할 때까지) 기다린다.
     *
     * @param activity 흐름을 띄울 Activity
     */
    public suspend fun start(activity: Activity, type: PlayUpdateType): PlayUpdateResult

    /** 유연 업데이트의 상태를 구독한다. 수집하는 동안만 Play 리스너가 등록된다. */
    public fun installStates(): Flow<PlayInstallState>

    /** 내려받은 유연 업데이트를 설치한다. 성공하면 앱이 재시작된다. */
    public suspend fun completeFlexibleUpdate(): PlayUpdateResult

    public companion object {
        /** Play 가 제공하는 실제 매니저로 만든다. */
        @JvmStatic
        public fun create(context: Context): PlayUpdateClient =
            DefaultPlayUpdateClient(AppUpdateManagerFactory.create(context.applicationContext))

        /**
         * 지정한 [AppUpdateManager] 로 만든다 — 테스트에서 `FakeAppUpdateManager` 를 끼울 때 쓴다.
         * 이 시그니처는 Play 타입을 드러내므로 일반 앱 코드에서는 [create] (Context) 를 쓴다.
         */
        @JvmStatic
        public fun create(manager: AppUpdateManager): PlayUpdateClient = DefaultPlayUpdateClient(manager)
    }
}
