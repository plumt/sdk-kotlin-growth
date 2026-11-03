package net.plumt.growth.review

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.testing.FakeReviewManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayReviewLauncherTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val fake = FakeReviewManager(context)

    @Test
    fun `정상 경로는 FlowFinished`() {
        assertEquals(ReviewLaunchResult.FlowFinished, runWithLooper { PlayReviewLauncher.create(fake).launch(activity) })
    }

    @Test
    fun `정보 요청이 실패하면 Failed 이고 원인이 보존된다`() {
        val boom = java.io.IOException("Play 없음")
        val manager = object : ReviewManager by fake {
            override fun requestReviewFlow(): Task<ReviewInfo> = Tasks.forException(boom)
        }
        val r = runWithLooper { PlayReviewLauncher.create(manager).launch(activity) } as ReviewLaunchResult.Failed
        assertSame(boom, r.cause.cause)
        assertTrue(r.cause.message!!.isNotBlank())
    }

    @Test
    fun `플로우 실행이 실패하면 Failed 이고 원인이 보존된다`() {
        val boom = IllegalStateException("플로우 오류")
        val manager = object : ReviewManager by fake {
            override fun launchReviewFlow(activity: Activity, info: ReviewInfo): Task<Void> = Tasks.forException(boom)
        }
        val r = runWithLooper { PlayReviewLauncher.create(manager).launch(activity) } as ReviewLaunchResult.Failed
        assertSame(boom, r.cause.cause)
    }

    @Test
    fun `Task 가 원인 없이 실패해도 Failed 이다`() {
        val manager = object : ReviewManager by fake {
            override fun requestReviewFlow(): Task<ReviewInfo> =
                TaskCompletionSource<ReviewInfo>().also { it.setException(RuntimeException()) }.task
        }
        assertTrue(runWithLooper { PlayReviewLauncher.create(manager).launch(activity) } is ReviewLaunchResult.Failed)
    }

    @Test
    fun `취소는 Failed 로 접지 않고 전파한다`() {
        val manager = object : ReviewManager by fake {
            override fun requestReviewFlow(): Task<ReviewInfo> = TaskCompletionSource<ReviewInfo>().task
        }
        runBlocking {
            val job = async(start = CoroutineStart.UNDISPATCHED) { PlayReviewLauncher.create(manager).launch(activity) }
            yield()
            job.cancel()
            assertThrows(CancellationException::class.java) { runBlocking { job.await() } }
        }
    }

    @Test
    fun `Context 로 만든 기본 런처도 생성된다`() {
        assertTrue(PlayReviewLauncher.create(context) is ReviewLauncher)
    }
}
