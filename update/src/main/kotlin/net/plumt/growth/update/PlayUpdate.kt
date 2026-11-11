package net.plumt.growth.update

/** Play 인앱 업데이트 방식. */
public enum class PlayUpdateType {
    /** 백그라운드로 내려받고 앱을 계속 쓰게 한다. 설치는 [PlayUpdateClient.completeFlexibleUpdate] 로 마무리한다. */
    FLEXIBLE,

    /** 전체 화면 흐름으로 업데이트가 끝날 때까지 앱을 막는다. */
    IMMEDIATE,
}

/** [PlayUpdateClient.availability] 의 결과. */
public sealed interface PlayUpdateAvailability {

    /** Play 에 새 버전이 없다. */
    public data object NotAvailable : PlayUpdateAvailability

    /** Play 가 상태를 알려주지 못했다(`UpdateAvailability.UNKNOWN`). */
    public data object Unknown : PlayUpdateAvailability

    /**
     * 새 버전이 있다.
     *
     * @property availableVersionCode Play 의 새 버전 코드
     * @property priority 개발자가 Play Developer API 로 지정한 우선순위(0..5)
     * @property stalenessDays 새 버전이 사용자에게 제공된 지 며칠 지났는지. 알 수 없으면 `null`
     * @property flexibleAllowed 이 기기에서 [PlayUpdateType.FLEXIBLE] 이 허용되는지
     * @property immediateAllowed 이 기기에서 [PlayUpdateType.IMMEDIATE] 가 허용되는지
     */
    public data class Available(
        val availableVersionCode: Int,
        val priority: Int,
        val stalenessDays: Int?,
        val flexibleAllowed: Boolean,
        val immediateAllowed: Boolean,
    ) : PlayUpdateAvailability {
        /** [type] 방식이 허용되는지. */
        public fun allows(type: PlayUpdateType): Boolean =
            if (type == PlayUpdateType.FLEXIBLE) flexibleAllowed else immediateAllowed
    }

    /** 즉시 업데이트가 진행 중이었다(앱이 다시 열렸다). Play 문서상 이어서 진행해야 한다. */
    public data object ImmediateInProgress : PlayUpdateAvailability

    /** 유연 업데이트를 내려받는 중이다. [PlayUpdateClient.installStates] 로 진행을 본다. */
    public data object Downloading : PlayUpdateAvailability

    /** 유연 업데이트를 내려받았고 설치만 남았다. [PlayUpdateClient.completeFlexibleUpdate] 를 부르면 앱이 재시작된다. */
    public data object Downloaded : PlayUpdateAvailability

    /** Play 에 묻지 못했다(Play 스토어 없음·서비스 오류 등). 원인은 [cause] 다. */
    public data class Failed(val cause: UpdateException) : PlayUpdateAvailability
}

/** Play 업데이트 흐름·설치 요청의 결과. */
public sealed interface PlayUpdateResult {

    /** 사용자가 수락했거나 요청이 접수됐다. 즉시 업데이트가 끝나면 앱은 재시작되므로 이 값을 못 받을 수 있다. */
    public data object Accepted : PlayUpdateResult

    /** 사용자가 취소했다. 강제 상황이면 차단을 유지하고 다시 안내한다. */
    public data object Canceled : PlayUpdateResult

    /**
     * Activity Result 런처로 흐름을 띄웠다. 사용자의 선택은 런처 콜백으로 오고([fromActivityResult] 로 바꾼다),
     * Activity 가 다시 만들어져도 Activity Result API 가 새 인스턴스에 전달한다. 다음 판단은 [AppUpdater.refresh] 로 한다.
     */
    public data object Launched : PlayUpdateResult

    /** 흐름이 실패했다. 원인은 [cause] 다. */
    public data class Failed(val cause: UpdateException) : PlayUpdateResult

    public companion object {
        /**
         * Play 업데이트 흐름을 띄운 Activity Result 런처의 결과 코드를 바꾼다.
         * `RESULT_OK` → [Accepted], `RESULT_CANCELED` → [Canceled], 그 밖(`RESULT_IN_APP_UPDATE_FAILED` 등) → [Failed].
         */
        @JvmStatic
        public fun fromActivityResult(resultCode: Int): PlayUpdateResult = when (resultCode) {
            android.app.Activity.RESULT_OK -> Accepted
            android.app.Activity.RESULT_CANCELED -> Canceled
            else -> Failed(UpdateException("Play 업데이트 흐름이 실패했습니다. 결과 코드: $resultCode"))
        }
    }
}

/**
 * 유연 업데이트 설치 상태의 종류.
 *
 * [REQUIRES_UI_INTENT] 는 Play 가 사용자 확인 화면을 다시 띄워야 진행할 수 있다는 뜻이다 — 흐름을 다시 시작한다([AppUpdater.refresh] 후 `start`).
 */
public enum class PlayInstallStatus {
    UNKNOWN, REQUIRES_UI_INTENT, PENDING, DOWNLOADING, DOWNLOADED, INSTALLING, INSTALLED, FAILED, CANCELED
}

/**
 * 유연 업데이트 진행 상태.
 *
 * @property bytesDownloaded 내려받은 바이트
 * @property totalBytesToDownload 내려받을 전체 바이트(모르면 0)
 * @property errorCode [PlayInstallStatus.FAILED] 일 때 Play 의 오류 코드, 아니면 0
 */
public data class PlayInstallState(
    val status: PlayInstallStatus,
    val bytesDownloaded: Long = 0,
    val totalBytesToDownload: Long = 0,
    val errorCode: Int = 0,
) {
    /** 0.0..1.0 진행률. 전체 크기를 모르면 `null`. */
    public val progress: Float?
        get() = if (totalBytesToDownload > 0) (bytesDownloaded.toFloat() / totalBytesToDownload).coerceIn(0f, 1f) else null
}
