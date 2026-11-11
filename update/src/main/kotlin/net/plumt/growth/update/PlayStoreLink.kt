package net.plumt.growth.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 이 앱의 Play 스토어 상세 페이지로 가는 링크. [UpdateAction.BlockUntilStoreUpdate] 때 "업데이트" 버튼이 쓴다.
 *
 * - [open] — Play 스토어 앱으로 열고, 없으면 웹 페이지로 연다. 둘 다 처리할 앱이 없으면 [UpdateException] 이다. 보통은 이것만 쓴다
 * - [intent] — `market://` 인텐트. Play 스토어 앱(`com.android.vending`)을 지정해 다른 스토어가 가로채지 않게 한다
 * - [webIntent] — `https://play.google.com` 인텐트
 *
 * Play 스토어가 없는 기기(다른 스토어로 배포한 빌드)에서는 웹 페이지로도 설치할 수 없다. 그런 배포 채널은 차단 화면의
 * 버튼이 그 스토어의 링크를 열도록 앱이 직접 구현한다.
 */
public object PlayStoreLink {

    /** Play 스토어 앱의 패키지 이름. */
    public const val PLAY_STORE_PACKAGE: String = "com.android.vending"

    /** Play 스토어 앱으로 `market://details?id=<패키지>` 를 여는 인텐트. 스토어 앱이 없으면 `startActivity` 가 `ActivityNotFoundException` 이다. */
    @JvmStatic
    public fun intent(context: Context): Intent =
        view("market://details?id=${context.packageName}").setPackage(PLAY_STORE_PACKAGE)

    /** `https://play.google.com/store/apps/details?id=<패키지>` 를 여는 인텐트. */
    @JvmStatic
    public fun webIntent(context: Context): Intent =
        view("https://play.google.com/store/apps/details?id=${context.packageName}")

    /**
     * 스토어 페이지를 연다 — Play 스토어 앱 → 웹 순으로 시도한다. 메인 스레드에서 부른다.
     *
     * @throws UpdateException 두 인텐트 모두 처리할 앱이 없을 때(원인은 마지막 `ActivityNotFoundException`)
     */
    @JvmStatic
    public fun open(context: Context) {
        try {
            context.startActivity(intent(context))
            return
        } catch (_: ActivityNotFoundException) {
            // 웹으로 넘어간다
        }
        try {
            context.startActivity(webIntent(context))
        } catch (e: ActivityNotFoundException) {
            throw UpdateException("Play 스토어 앱도 웹 브라우저도 없어 스토어 페이지를 열 수 없습니다.", e)
        }
    }

    private fun view(uri: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
