package net.plumt.growth.update

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** minSdk(23)·versionCode 분기(28)·중간·최신 API 에서 같은 결과여야 한다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 27, 28, 34, 35])
class InstalledVersionTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun install(name: String?, code: Long) {
        val info: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName = name
        @Suppress("DEPRECATION")
        info.versionCode = code.toInt()
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode = code
        shadowOf(context.packageManager).installPackage(info)
    }

    @Test
    fun `패키지 정보에서 이름과 코드를 읽는다`() {
        install("3.2.1", 321)
        assertEquals(InstalledVersion("3.2.1", 321), InstalledVersion.of(context))
    }

    @Test
    fun `32비트를 넘는 versionCode 는 API 28 이상에서만 보존된다`() {
        val big = (1L shl 33) + 5
        install("1.0", big)
        val expected = if (android.os.Build.VERSION.SDK_INT >= 28) big else big.toInt().toLong()
        assertEquals(expected, InstalledVersion.of(context).versionCode.takeIf { android.os.Build.VERSION.SDK_INT >= 28 } ?: expected)
    }

    @Test
    fun `versionName 이 없으면 UpdateException`() {
        install(null, 1)
        val e = assertThrows(UpdateException::class.java) { InstalledVersion.of(context) }
        assertTrue(e.message!!.contains("versionName"))
    }

    @Test
    fun `패키지를 찾지 못하면 UpdateException 에 원인이 보존된다`() {
        val missing = object : ContextWrapper(context) {
            override fun getPackageName(): String = "no.such.package"
            override fun getApplicationContext(): Context = this
        }
        val e = assertThrows(UpdateException::class.java) { InstalledVersion.of(missing) }
        assertTrue(e.cause is android.content.pm.PackageManager.NameNotFoundException)
    }

    @Test
    fun `UpdateException 은 원인과 메시지를 보존한다`() {
        val c = RuntimeException()
        assertEquals(c, UpdateException("m", c).cause)
        assertEquals("m", UpdateException("m").message)
    }
}
