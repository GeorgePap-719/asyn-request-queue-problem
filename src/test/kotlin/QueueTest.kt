package asynqueueproblem.test

import asynqueueproblem.*
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertContains

class QueueTest {
    @Test
    fun test(): Unit = runBlocking(WorkerScope.coroutineContext) {
        val queue = queueWithInitialValue {
            async { println("1") }
        }
        queue.enqueue {
            async { println("2") }
        }
        queue.enqueue {
            delay(1000)
            async { println("3") }
        }
        queue.enqueue {
            delay(1000)
            async { println("4") }
        }
        queue.enqueue {
            delay(1000)
            async { println("5") }
        }
        queue.enqueue {
            delay(1000)
            async { println("6") }
        }
        println("queue size: ${queue.size}")
        val jobs = buildList {
            for (node in 0 until queue.size) {
                add(launch { queue.dequeue() })
            }
        }
        jobs.forEach { it.join() }
        println("queue size: ${queue.size}")
        assert(queue.isEmpty)
    }

    @Test
    fun testQueueWith1000Items(): Unit = runBlocking(WorkerScope.coroutineContext) {
        val queue = emptyQueue()
        val args =
            arrayOfNulls<Int>(1000) // gen array to verify state. Choose array instead of list with prefix size to avoid
        // resize, since resizing needs synchronization.
        for (i in 1..1000) {
            queue.enqueue {
                args[i - 1] = i
                async { i.also { println(it) } }
            }
        }
        println("size of queue:${queue.size}")
        val jobs = buildList {
            for (node in 0 until queue.size) {
                add(launch {
                    when (val result = queue.dequeue()) {
                        Failure -> throw AssertionError("queue is empty")
                        is Success -> assertContainsAndRemove(args, result.value as Int)
                    }
                })
            }
        }
        jobs.forEach { it.join() }
        assert(queue.isEmpty) { "queue should be empty in the end" }
        assert(args.isEmpty) { "args array should be empty in the end" }
    }

    private fun assertContainsAndRemove(source: Array<Int?>, value: Int) {
        assertContains(source, value)
        source[value - 1] = null
    }

    private val Array<Int?>.isEmpty: Boolean
        get() {
            for (i in this) if (i == null) return true
            return false
        }
}