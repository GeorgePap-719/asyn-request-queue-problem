package asynqueueproblem

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

fun main(): Unit = runBlocking(WorkerScope.coroutineContext) {
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