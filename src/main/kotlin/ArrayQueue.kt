package asynqueueproblem

import kotlinx.atomicfu.AtomicInt
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicReference
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

class ArrayQueue(
    initialValue: QueueJob? = null,
    /**
     * Sets array's maximum capacity.
     */
    sizeOfArray: Int = 10,
    /**
     * Sets the maximum available workers to process jobs concurrently.
     */
    private val numberOfWorkers: Int = 3,
) {
    // Use AtomicReference instead of AtomicRef, as workaround for https://github.com/Kotlin/kotlinx-atomicfu/issues/293
    private val array: Array<AtomicReference<QueueJob?>> = Array(sizeOfArray) { AtomicReference(null) }
    private val _size = atomic(0)

    // index for head and tail position.
    private val head = atomic(0)
    private val tail = atomic(0)

    //TODO: add KDoc
    @Suppress("NOTHING_TO_INLINE")
    private inline fun AtomicInt.moveIndexForward() {
        update { (it + 1).mod(array.size) }
    }

    private fun moveHeadForwardAndDecrSize() {
        head.moveIndexForward()
        _size.decrementAndGet()
    }

    private fun moveTailForwardAndIncrSize() {
        tail.moveIndexForward()
        _size.incrementAndGet()
    }

    //
    private val queueAvailableForDequeue = Semaphore(1)

    // Represent workers as semaphores to allow suspension when there is no worker available, instead of looping
    // continuously for free worker.
    private val workers = Semaphore(numberOfWorkers)

    private val hasCapacity: Boolean get() = _size.value < array.size
    private val isFull: Boolean get() = _size.value == array.size

    init {
        require(numberOfWorkers > 0) { "number of workers cannot be 0 or negative, but got:$numberOfWorkers" }
        if (initialValue != null) {
            array[0] = AtomicReference(initialValue)
            _size.incrementAndGet()
        }
    }


    val size: Int get() = _size.value
    val isEmpty: Boolean get() = size == 0

    // Returns false if array is full else true.
    // Suspends when there is not an available worker.
    suspend fun tryEnqueue(value: QueueJob): Boolean {
        workers.withPermit {
            while (true) {
                if (isFull) return false
                if (array[tail.value].compareAndSet(null, value)) {
                    moveTailForwardAndIncrSize()
                    if (queueAvailableForDequeue.availablePermits == 0) {
                        // This needed in order to allow consumers suspend when they try to dequeue from queue when is empty.
                        queueAvailableForDequeue.release() // signal waiters that queue is ready for dequeue.
                    }
                    return true // value is inserted.
                }
            }
        }
    }

    // Returns value or Failure if queue is empty.
    // Suspends when there is not an available worker.
    suspend fun dequeue(): QueueResult {
        workers.withPermit {
            while (true) { // fast-path, queue is not empty and item is ready for retrieval.
                if (isEmpty) return@withPermit // move to slow-path
                val job = array[head.value].get() ?: continue // loop until there is an available head to remove.
                moveHeadForwardAndDecrSize()
                // if queue is empty cause of this `dequeue` operation, then acquire permit (signal queue is empty), if
                // there is one.
                if (isEmpty) queueAvailableForDequeue.tryAcquire()
                return Success(job.invoke(WorkerScope).await())
            }
        }
        // queue is empty at this point.
        while (true) { // slow-path, loop until there is an available item to retrieve.
            queueAvailableForDequeue.acquire() // suspend until queue has an item ready for retrieval.
            workers.withPermit {
                if (isEmpty) return@withPermit // continue, suspend again until there is an available item.
                val job = array[head.value].get()
                    ?: return@withPermit // continue, suspend again until there is an available item.
                moveHeadForwardAndDecrSize()
                if (hasCapacity) queueAvailableForDequeue.release() // release sem only if this item was not the last one.
                return Success(job.invoke(WorkerScope).await())
            }
        }
    }

    @OptIn(ExperimentalContracts::class)
    private suspend inline fun <T> Semaphore.suspendUntilItemInsertion(action: () -> T): T {
        contract {
            callsInPlace(action, InvocationKind.EXACTLY_ONCE)
        }
        acquire() // suspend until an item is inserted
        try {
            return action()
        } finally {
            if (!isEmpty) release() // release sem, only if queue is not empty.
        }
    }
}

fun emptyArrayQueue(): ArrayQueue = ArrayQueue()