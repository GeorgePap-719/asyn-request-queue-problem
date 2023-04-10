package asynqueueproblem.test

import asynqueueproblem.ArrayQueue
import asynqueueproblem.WorkerScope
import asynqueueproblem.emptyArrayQueue
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource
import kotlin.time.measureTime

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
        assert((queue.dequeue()) == 10)
        assert(queue.isEmpty)
    }

    @Test
    fun stressTestWithBlockingProducer(): Unit = runBlocking(WorkerScope.coroutineContext) {
        val capacity = 100_000
        val args =
            arrayOfNulls<Int>(capacity) // gen array to verify state. Choose array instead of list with prefix size to avoid
        // resize, since resizing needs synchronization.
        val queue = ArrayQueue(capacity = capacity)
        for (i in 1..capacity) {
            args[i - 1] = i // fill array
            assert(queue.tryEnqueue {
                async { i }
            })
        }
        assert(queue.size == capacity)
        val jobs = buildList {
            for (item in 0 until queue.size) {
                add(launch {
                    assertContainsAndRemove(args, queue.dequeue() as Int)
                })
            }
        }
        jobs.forEach { it.join() }
        assert(queue.isEmpty)
        assert(args.isEmpty)
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun stressTestWithMultipleProducersAndConsumers(): Unit = runBlocking(WorkerScope.coroutineContext) {
        withTimeout(Duration.parse("60s")) {
            val elapsed = TimeSource.Monotonic.measureTime {
                val capacity = 100_000
                val args =
                    arrayOfNulls<Int>(capacity) // gen array to verify state. Choose array instead of list with prefix size to avoid
                // resize, since resizing needs synchronization.
                val queue = ArrayQueue(capacity = capacity)
                for (i in 1..capacity) {
                    launch {
                        args[i - 1] = i // fill array
                        assert(queue.tryEnqueue {
                            async { i }
                        })
                    }
                }

                val jobs = buildList {
                    for (item in 0 until capacity) { // size is unpredictable at this moment
                        add(launch {
                            assertContainsAndRemove(args, queue.dequeue() as Int)
                        })
                    }
                }
                jobs.forEach { it.join() }
                assert(queue.isEmpty)
                assert(args.isEmpty)
            }
            println("elapsed:$elapsed")
        }

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