package net.plumt.growth.update

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 이 앱의 Play 스토어 상세 페이지로 가는 인텐트. [UpdateAction.BlockUntilStoreUpdate] 때 "업데이트" 버튼이 쓴다.
 *
 * 스토어 앱이 없는 기기를 위해 [intent] 는 `market://` 이고 [webIntent] 는 `https://play.google.com` 이다.
 * `market://` 를 처리할 앱이 없으면 [webIntent] 로 대체하는 것은 호출부 몫이다(`startActivity` 의 `ActivityNotFoundException`).
 */
public object PlayStoreLink {

    /** `market://details?id=<패키지>` 를 여는 인텐트. */
    @JvmStatic
    public fun intent(context: Context): Intent = view("market://details?id=${context.packageName}")

    /** `https://play.google.com/store/apps/details?id=<패키지>` 를 여는 인텐트. */
    @JvmStatic
    public fun webIntent(context: Context): Intent =
        view("https://play.google.com/store/apps/details?id=${context.packageName}")

    private fun view(uri: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
