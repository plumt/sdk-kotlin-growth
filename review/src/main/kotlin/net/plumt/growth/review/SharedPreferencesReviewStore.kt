package net.plumt.growth.review

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ReviewStore] 의 기본 구현 — `SharedPreferences` 한 파일에 저장한다.
 *
 * 읽기·쓰기는 `Dispatchers.IO` 에서 하며 쓰기는 `commit()` 으로 끝까지 반영한다.
 * 저장 항목은 카운터와 시각, 앱 버전 문자열뿐이라 개인정보를 담지 않는다.
 *
 * @param fileName 저장 파일 이름. 앱의 다른 설정 파일과 겹치지 않게 바꿀 수 있다
 */
public class SharedPreferencesReviewStore @JvmOverloads constructor(
    context: Context,
    fileName: String = DEFAULT_FILE_NAME,
) : ReviewStore {

    private val prefs: SharedPreferences by lazy {
        context.applicationContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)
    }

    init {
        require(fileName.isNotBlank()) { "fileName 은 비어 있을 수 없습니다. 받은 값: \"$fileName\"" }
    }

    override suspend fun load(): ReviewState = withContext(Dispatchers.IO) {
        ReviewState(
            firstLaunchAtMillis = prefs.longOrNull(KEY_FIRST_LAUNCH),
            launchCount = prefs.getInt(KEY_LAUNCH_COUNT, 0),
            significantEventCount = prefs.getInt(KEY_EVENT_COUNT, 0),
            lastRequestedAtMillis = prefs.longOrNull(KEY_LAST_REQUESTED),
            lastDeclinedAtMillis = prefs.longOrNull(KEY_LAST_DECLINED),
            lastNegativeAtMillis = prefs.longOrNull(KEY_LAST_NEGATIVE),
            versionOfRequests = prefs.getString(KEY_REQUEST_VERSION, null),
            requestsInVersion = prefs.getInt(KEY_REQUEST_COUNT, 0),
        )
    }

    override suspend fun save(state: ReviewState): Unit = withContext(Dispatchers.IO) {
        val committed = prefs.edit()
            .putLongOrRemove(KEY_FIRST_LAUNCH, state.firstLaunchAtMillis)
            .putInt(KEY_LAUNCH_COUNT, state.launchCount)
            .putInt(KEY_EVENT_COUNT, state.significantEventCount)
            .putLongOrRemove(KEY_LAST_REQUESTED, state.lastRequestedAtMillis)
            .putLongOrRemove(KEY_LAST_DECLINED, state.lastDeclinedAtMillis)
            .putLongOrRemove(KEY_LAST_NEGATIVE, state.lastNegativeAtMillis)
            .apply {
                if (state.versionOfRequests == null) remove(KEY_REQUEST_VERSION)
                else putString(KEY_REQUEST_VERSION, state.versionOfRequests)
            }
            .putInt(KEY_REQUEST_COUNT, state.requestsInVersion)
            .commit()
        if (!committed) throw ReviewException("리뷰 카운터를 SharedPreferences 에 저장하지 못했습니다.")
    }

    private fun SharedPreferences.longOrNull(key: String): Long? =
        if (contains(key)) getLong(key, 0L) else null

    private fun SharedPreferences.Editor.putLongOrRemove(key: String, value: Long?): SharedPreferences.Editor =
        if (value == null) remove(key) else putLong(key, value)

    public companion object {
        /** 기본 저장 파일 이름. */
        public const val DEFAULT_FILE_NAME: String = "net.plumt.growth.review"

        private const val KEY_FIRST_LAUNCH = "first_launch_at"
        private const val KEY_LAUNCH_COUNT = "launch_count"
        private const val KEY_EVENT_COUNT = "significant_event_count"
        private const val KEY_LAST_REQUESTED = "last_requested_at"
        private const val KEY_LAST_DECLINED = "last_declined_at"
        private const val KEY_LAST_NEGATIVE = "last_negative_at"
        private const val KEY_REQUEST_VERSION = "request_version"
        private const val KEY_REQUEST_COUNT = "request_count"
    }
}
