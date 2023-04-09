package asynqueueproblem.test

import asynqueueproblem.Success
import asynqueueproblem.emptyArrayQueue
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ArrayQueueTest {

    @Test
    fun basicTest(): Unit = runBlocking {
        val queue = emptyArrayQueue()
        assert(queue.isEmpty)
        for (i in 1..10) {
            queue.tryEnqueue { async { i.also { println(it) } } }
        }
        assert(!queue.tryEnqueue {
            async { 10 }
        })
        println("queue size:${queue.size}")
        val jobs = buildList {
            for (item in 0 until queue.size) {
                add(launch { queue.dequeue() })
            }
        }
        jobs.forEach { it.join() }
        println("queue size:${queue.size}")
        assert(queue.isEmpty)
        assert(queue.tryEnqueue {
            async { 10 }
        })
        assert((queue.dequeue() as Success).value == 10)
    }

    //@Test
    fun shouldSuspendWhenDequeuingEmptyQueue(): Unit = runTest {
        val queue = emptyArrayQueue()
        queue.dequeue()
        error("should not reach here")
    }

}