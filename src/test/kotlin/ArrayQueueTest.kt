package asynqueueproblem.test

import asynqueueproblem.emptyArrayQueue
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class ArrayQueueTest {

    @Test
    fun basicTest(): Unit = runBlocking {
        val queue = emptyArrayQueue()
        for (i in 1..10) {
            queue.tryEnqueue { async { i/*.also { println(it) }*/ } }
        }
        println("queue size:${queue.size}")
        val jobs = buildList {
            for (item in 0 until queue.size) {
                add(launch { queue.dequeue() })
            }
        }
        jobs.forEach { it.join() }
        assert(queue.isEmpty)
    }
}