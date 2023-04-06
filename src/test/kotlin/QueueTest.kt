package asynqueueproblem.test

import asynqueueproblem.WorkerScope
import asynqueueproblem.emptyQueue
import asynqueueproblem.queueWithInitialValue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

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
    }

    @Test
    fun testQueueWith1000Items(): Unit = runBlocking {
        val queue = emptyQueue()
        for (i in 1..1000) {
            queue.enqueue {
                async { println("$i") }
            }
        }
        println("size of queue:${queue.size}")
        for (node in 0 until queue.size) {
            launch { queue.dequeue() }
        }
    }
}