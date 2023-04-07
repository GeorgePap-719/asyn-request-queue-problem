package asynqueueproblem.test

//import asynqueueproblem.emptyArrayQueue
import asynqueueproblem.QueueJob
import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import org.junit.jupiter.api.Test

class ArrayQueueTest {

//    @Test
//    fun basicTest(): Unit = runBlocking {
//        val queue = emptyArrayQueue()
//        for (i in 1..10) {
//            queue.tryEnqueue { async { i/*.also { println(it) }*/ } }
//        }
//        println("queue size:${queue.size}")
//        val jobs = buildList {
//            for (item in 0 until queue.size) {
//                add(launch { queue.dequeue() })
//            }
//        }
//        jobs.forEach { it.join() }
//        assert(queue.isEmpty)
//    }

    @Test
    fun testAtomicFU() {
        val test = SomeClass()
    }

    class SomeClass {
        private val array: Array<AtomicRef<QueueJob?>> = Array(10) { atomic(null) }
    }
}