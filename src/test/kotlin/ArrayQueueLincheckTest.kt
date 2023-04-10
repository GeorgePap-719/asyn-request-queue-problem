package asynqueueproblem.test

import asynqueueproblem.emptyArrayQueue
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.jupiter.api.Test

class ArrayQueueLincheckTest /*: VerifierState()*/ {
    private val queue = emptyArrayQueue()
//    private val counter = atomic(0)

    @Operation
    fun enqueue(e: Int): Unit = runBlocking {
        queue.tryEnqueue { async { e } }
    }

    @Operation
    fun dequeue() = runBlocking {
        queue.dequeue() as Int
    }

    // this fails
    @Test
    fun modelCheckingTest() = ModelCheckingOptions().check(this::class)

    //    @Test // Run the test
    fun stressTest() = StressOptions().check(this::class)
}