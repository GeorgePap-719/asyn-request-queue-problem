package asynqueueproblem

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/*
 * We cheat a bit here, since we use only one thead.
 */
fun main(): Unit = runBlocking {
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