package net.plumt.growth.review

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 34, 35])
class ReviewStateAndStoreTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    private val full = ReviewState(
        firstLaunchAtMillis = 10L, launchCount = 3, significantEventCount = 2, lastRequestedAtMillis = 20L,
        lastDeclinedAtMillis = 30L, lastNegativeAtMillis = 40L, versionOfRequests = "2.0", requestsInVersion = 1,
    )

    @Test
    fun `ReviewState 는 음수 카운터를 거부한다 - 메시지에 받은 값`() {
        assertEquals("launchCount 는 0 이상이어야 합니다. 받은 값: -1", assertThrows(IllegalArgumentException::class.java) { ReviewState(launchCount = -1) }.message)
        assertEquals("significantEventCount 는 0 이상이어야 합니다. 받은 값: -2", assertThrows(IllegalArgumentException::class.java) { ReviewState(significantEventCount = -2) }.message)
        assertEquals("requestsInVersion 은 0 이상이어야 합니다. 받은 값: -3", assertThrows(IllegalArgumentException::class.java) { ReviewState(requestsInVersion = -3) }.message)
    }

    @Test
    fun `requestsFor 는 같은 버전일 때만 센다`() {
        assertEquals(1, full.requestsFor("2.0"))
        assertEquals(0, full.requestsFor("2.1"))
        assertEquals(0, ReviewState().requestsFor(""))
    }

    @Test
    fun `메모리 저장소는 초기값과 저장값을 돌려준다`() = runBlocking {
        assertEquals(full, InMemoryReviewStore(full).load())
        val s = InMemoryReviewStore()
        assertEquals(ReviewState(), s.load())
        s.save(full)
        assertEquals(full, s.load())
    }

    @Test
    fun `SharedPreferences 저장소는 모든 필드를 왕복한다 - 프로세스 재시작은 저장소 재생성으로 흉내낸다`() = runBlocking {
        SharedPreferencesReviewStore(context, "t1").save(full)
        assertEquals(full, SharedPreferencesReviewStore(context, "t1").load())
    }

    @Test
    fun `null 로 되돌린 필드는 지워진다`() = runBlocking {
        val store = SharedPreferencesReviewStore(context, "t2")
        store.save(full)
        store.save(full.copy(lastRequestedAtMillis = null, versionOfRequests = null, firstLaunchAtMillis = null))
        val loaded = store.load()
        assertNull(loaded.lastRequestedAtMillis)
        assertNull(loaded.versionOfRequests)
        assertNull(loaded.firstLaunchAtMillis)
        assertEquals(30L, loaded.lastDeclinedAtMillis)
    }

    @Test
    fun `0 과 Long 경계값도 null 과 구별된다`() = runBlocking {
        val store = SharedPreferencesReviewStore(context, "t3")
        store.save(ReviewState(firstLaunchAtMillis = 0L, lastRequestedAtMillis = Long.MAX_VALUE, lastNegativeAtMillis = Long.MIN_VALUE))
        val l = store.load()
        assertEquals(0L, l.firstLaunchAtMillis)
        assertEquals(Long.MAX_VALUE, l.lastRequestedAtMillis)
        assertEquals(Long.MIN_VALUE, l.lastNegativeAtMillis)
    }

    @Test
    fun `유니코드와 긴 버전 문자열을 보존한다`() = runBlocking {
        val v = "버전-🌙-" + "9".repeat(5000)
        val store = SharedPreferencesReviewStore(context, "t4")
        store.save(ReviewState(versionOfRequests = v, requestsInVersion = 1))
        assertEquals(v, store.load().versionOfRequests)
    }

    @Test
    fun `파일 이름이 다르면 서로 간섭하지 않는다`() = runBlocking {
        SharedPreferencesReviewStore(context, "a").save(full)
        assertNotEquals(full, SharedPreferencesReviewStore(context, "b").load())
    }

    @Test
    fun `기본 파일 이름과 빈 파일 이름 검사`() {
        assertEquals("net.plumt.growth.review", SharedPreferencesReviewStore.DEFAULT_FILE_NAME)
        assertEquals(ReviewState(), runBlocking { SharedPreferencesReviewStore(context).load() })
        val e = assertThrows(IllegalArgumentException::class.java) { SharedPreferencesReviewStore(context, " ") }
        assertTrue(e.message!!.contains("fileName"))
    }

    @Test
    fun `commit 이 실패하면 ReviewException`() {
        val failing = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                val real = context.getSharedPreferences(name, mode)
                return object : SharedPreferences by real {
                    override fun edit(): SharedPreferences.Editor {
                        val e = real.edit()
                        return object : SharedPreferences.Editor by e {
                            override fun putLong(k: String?, v: Long) = apply { e.putLong(k, v) }
                            override fun putInt(k: String?, v: Int) = apply { e.putInt(k, v) }
                            override fun putString(k: String?, v: String?) = apply { e.putString(k, v) }
                            override fun remove(k: String?) = apply { e.remove(k) }
                            override fun commit(): Boolean = false
                        }
                    }
                }
            }
        }
        val e = assertThrows(ReviewException::class.java) { runBlocking { SharedPreferencesReviewStore(failing, "t5").save(full) } }
        assertTrue(e.message!!.contains("SharedPreferences"))
    }

    @Test
    fun `ReviewException 은 원인과 메시지를 보존한다`() {
        val cause = RuntimeException("x")
        val e = ReviewException("m", cause)
        assertEquals("m", e.message)
        assertEquals(cause, e.cause)
        assertNull(ReviewException("n").cause)
    }

    @Test
    fun `시스템 시계는 현재 시각에 가깝다`() {
        val before = System.currentTimeMillis()
        val t = ReviewClock.System.nowMillis()
        assertTrue(t in before..System.currentTimeMillis())
    }
}
