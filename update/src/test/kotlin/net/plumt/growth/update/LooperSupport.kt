package net.plumt.growth.update

import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.robolectric.Shadows.shadowOf

/**
 * Play Task 의 콜백은 메인 루퍼로 오므로, 코루틴을 돌리면서 루퍼를 반복해 비운다.
 * [onTick] 은 매 반복마다 불려 사용자의 수락·거절을 흉내낼 수 있다. 끝나지 않으면 멈추지 않고 실패시킨다(행 방지).
 */
internal fun <T> runWithLooper(maxSpins: Int = 5000, onTick: () -> Unit = {}, block: suspend () -> T): T = runBlocking {
    val done = CompletableDeferred<T>()
    val job = launch {
        try {
            done.complete(block())
        } catch (e: Throwable) {
            done.completeExceptionally(e)
        }
    }
    var spins = 0
    while (!done.isCompleted) {
        check(spins++ < maxSpins) { "루퍼를 $maxSpins 번 돌렸지만 코루틴이 끝나지 않았습니다." }
        shadowOf(Looper.getMainLooper()).idle()
        onTick()
        yield()
    }
    job.join()
    done.await()
}
