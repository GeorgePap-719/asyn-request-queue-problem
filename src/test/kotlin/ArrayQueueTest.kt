package asynqueueproblem.test

import asynqueueproblem.emptyArrayQueue
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import org.junit.jupiter.api.Test
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

class ArrayQueueTest {

    @Test
    fun basicTest(): Unit = runBlocking {
        val queue = emptyArrayQueue()
        for (i in 1..10) {
            queue.tryEnqueue { async { i.also { println(it) } } }
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

    private val sem = Semaphore(2)

    //@Test
    fun testSem(): Unit = runBlocking {
        println(test())
    }

    private var testCounter = 0

    private suspend fun test(): String {
        if (++testCounter == 5) return "end"
        println("first line in test")
        sem.withPermitAndDebugger {
            return test()
        }
    }
}

@OptIn(ExperimentalContracts::class)
suspend inline fun <T> Semaphore.withPermitAndDebugger(action: () -> T): T {
    contract {
        callsInPlace(action, InvocationKind.EXACTLY_ONCE)
    }

    acquire()
    println("acquiring perm")
    try {
        return action()
    } finally {
        release()
        println("releasing perm")
    }
}

