package net.plumt.growth.review

import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.robolectric.Shadows.shadowOf

/** Play Task 콜백은 메인 루퍼로 오므로 코루틴을 돌리며 루퍼를 비운다. 끝나지 않으면 행 대신 실패시킨다. */
internal fun <T> runWithLooper(maxSpins: Int = 5000, block: suspend () -> T): T = runBlocking {
    val done = CompletableDeferred<T>()
    val job = launch {
        try { done.complete(block()) } catch (e: Throwable) { done.completeExceptionally(e) }
    }
    var spins = 0
    while (!done.isCompleted) {
        check(spins++ < maxSpins) { "루퍼를 $maxSpins 번 돌렸지만 코루틴이 끝나지 않았습니다." }
        shadowOf(Looper.getMainLooper()).idle()
        yield()
    }
    job.join()
    done.await()
}
