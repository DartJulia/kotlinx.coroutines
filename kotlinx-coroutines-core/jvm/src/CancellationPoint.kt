/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines

import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.loop
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Makes a blocking code block cancellable (become a cancellation point of the coroutine).
 *
 * The blocking code block will be interrupted and this function will throw [CancellationException]
 * if the coroutine is cancelled.
 *
 * Example:
 * ```
 * GlobalScope.launch(Dispatchers.IO) {
 *     async {
 *         // This function will throw [CancellationException].
 *         runInterruptible {
 *             doSomethingUseful()
 *
 *             // This blocking procedure will be interrupted when this coroutine is canceled
 *             // by Exception thrown by the below async block.
 *             doSomethingElseUsefulInterruptible()
 *         }
 *     }
 *
 *     async {
 *        delay(500L)
 *        throw Exception()
 *     }
 * }
 * ```
 *
 * There is also an optional context parameter to this function to enable single-call conversion of
 * interruptible Java methods into main-safe suspending functions like this:
 * ```
 * // With one call here we are moving the call to Dispatchers.IO and supporting interruption.
 * suspend fun <T> BlockingQueue<T>.awaitTake(): T =
 *         runInterruptible(Dispatchers.IO) { queue.take() }
 * ```
 *
 * @param context additional to [CoroutineScope.coroutineContext] context of the coroutine.
 * @param block regular blocking block that will be interrupted on coroutine cancellation.
 */
public suspend fun <T> runInterruptible(
        context: CoroutineContext = EmptyCoroutineContext,
        block: () -> T
): T {
    // fast path: empty context
    if (context === EmptyCoroutineContext) { return runInterruptibleInExpectedContext(block) }
    // slow path:
    return withContext(context) { runInterruptibleInExpectedContext(block) }
}

private suspend fun <T> runInterruptibleInExpectedContext(block: () -> T): T =
        suspendCoroutineUninterceptedOrReturn sc@{ uCont ->
            try {
                // fast path: no job
                val job = uCont.context[Job] ?: return@sc block()
                // slow path
                val threadState = ThreadState().apply { initInterrupt(job) }
                try {
                    block()
                } finally {
                    threadState.clearInterrupt()
                }
            } catch (e: InterruptedException) {
                throw CancellationException()
            }
        }

private class ThreadState {

    fun initInterrupt(job: Job) {
        // starts with Init
        if (state.value !== Init) throw IllegalStateException("impossible state")
        // remembers this running thread
        state.value = Working(Thread.currentThread(), null)
        // watches the job for cancellation
        val cancelHandle =
                job.invokeOnCompletion(onCancelling = true, invokeImmediately = true, handler = CancelHandler())
        // remembers the cancel handle or drops it
        state.loop { s ->
            when {
                s is Working -> if (state.compareAndSet(s, Working(s.thread, cancelHandle))) return
                s === Interrupting || s === Interrupted -> return
                s === Init || s === Finish -> throw IllegalStateException("impossible state")
                else -> throw IllegalStateException("unknown state")
            }
        }
    }

    fun clearInterrupt() {
        state.loop { s ->
            when {
                s is Working -> if (state.compareAndSet(s, Finish)) { s.cancelHandle!!.dispose(); return }
                s === Interrupting -> Thread.yield() // eases the thread
                s === Interrupted -> { Thread.interrupted(); return } // no interrupt leak
                s === Init || s === Finish -> throw IllegalStateException("impossible state")
                else -> throw IllegalStateException("unknown state")
            }
        }
    }

    private inner class CancelHandler : CompletionHandler {
        override fun invoke(cause: Throwable?) {
            state.loop { s ->
                when {
                    s is Working -> {
                        if (state.compareAndSet(s, Interrupting)) {
                            s.thread!!.interrupt()
                            state.value = Interrupted
                            return
                        }
                    }
                    s === Finish -> return
                    s === Interrupting || s === Interrupted -> return
                    s === Init -> throw IllegalStateException("impossible state")
                    else -> throw IllegalStateException("unknown state")
                }
            }
        }
    }

    private val state: AtomicRef<State> = atomic(Init)

    private interface State
    // initial state
    private object Init : State
    // cancellation watching is setup and/or the continuation is running
    private data class Working(val thread: Thread?, val cancelHandle: DisposableHandle?) : State
    // the continuation done running without interruption
    private object Finish : State
    // interrupting this thread
    private object Interrupting: State
    // done interrupting
    private object Interrupted: State
}
