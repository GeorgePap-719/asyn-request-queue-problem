package asynqueueproblem

import kotlinx.atomicfu.AtomicInt
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicReference

/**
 * A concurrent queue implementation using a fixed-length array to store items.
 *
 * The core idea is that each item in the queue represents a [job][QueueJob], which needs to be processed.
 * Each job can only be processed by a free "worker", if there is one available, otherwise it suspends until a worker
 * becomes free. By extension, the number of workers represent the queue's parallelism potential.
 *
 * Important notes:
 * * While queue is a FIFO data structure, it is possible that one job might finish earlier from another which has
 * started beforehand. Only the sequence of job execution can be guaranteed.
 */
class ArrayQueue(
    initialValue: QueueJob? = null,
    /**
     * Sets array's maximum capacity.
     */
    private val capacity: Int = 10,
    /**
     * Sets the maximum available workers to process jobs concurrently.
     */
    private val numberOfWorkers: Int = 3,
) {
    // Use AtomicReference instead of AtomicRef, as workaround for https://github.com/Kotlin/kotlinx-atomicfu/issues/293
    private val array: Array<AtomicReference<QueueJob?>> = Array(capacity) { AtomicReference(null) }
    private val _size = atomic(0)

    // indexes for head and tail position.
    private val head = atomic(0)
    private val tail = atomic(0)

    /**
     * Moves forward the index (head or tail), by updating it based on "(current_position + 1).mod(capacity)" formula.
     * This allows us to treat the array as a closed circle, and letting head and tail drift endlessly in that circle
     * makes it unnecessary to ever move items stores in the array.
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun AtomicInt.moveIndexForward() {
        update { (it + 1).mod(capacity) }
    }

    private fun moveHeadForwardAndDecSize() {
        head.moveIndexForward()
        _size.decrementAndGet()
    }

    private fun moveTailForwardAndIncSize() {
        tail.moveIndexForward()
        _size.incrementAndGet()
    }

    private fun tryAddLast(value: QueueJob): Boolean {
        if (!array[tail.value].compareAndSet(null, value)) return false
        moveTailForwardAndIncSize()
        return true
    }

    private fun removeFirstOrNull(): QueueJob? {
        while (true) {
            val job = array[head.value].get() ?: return null
            if (!array[head.value].compareAndSet(job, null)) continue
            moveHeadForwardAndDecSize()
            return job
        }
    }

    private val queueCapacityState = QueueCapacityState()

    // Represent workers as semaphores to allow suspension when there is not a worker available, instead of looping
    // continuously for a free worker.
    private val workers = Semaphore(numberOfWorkers)

    private val hasCapacity: Boolean get() = _size.value < capacity
    private val isFull: Boolean get() = _size.value == capacity

    init {
        require(numberOfWorkers > 0) { "number of workers cannot be 0 or negative, but got:$numberOfWorkers" }
        require(capacity > 0) { "capacity of array cannot be 0 or negative, but got:$capacity" }
        if (initialValue != null) {
            tryAddLast(initialValue)
            queueCapacityState.signalQueueIsNotEmpty()
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
                if (tryAddLast(value)) {
                    // This needed in order to allow consumers suspend when they try to dequeue from queue when is empty.
                    queueCapacityState.signalQueueIsNotEmpty()
                    return true // value is inserted.
                }
            }
        }
    }

    // Returns value or suspends if queue is empty.
    // Also, suspends when there is not an available worker.
    suspend fun dequeue(): Any? {
        workers.withPermit {
            while (true) { // fast-path, queue is not empty and item is ready for retrieval.
                if (isEmpty) return@withPermit // go-to slow-path
                val job = removeFirstOrNull() ?: continue // loop until there is an available head to remove.
                // if queue is empty cause of this `dequeue` operation, signal queue is empty.
                if (isEmpty) queueCapacityState.signalQueueIsEmpty()
                return job.invoke(WorkerScope).await()
            }
        }
        // queue is empty at this point, suspend caller and wait for next `enqueue`.
        return dequeueSlowPath()
    }

    private suspend fun dequeueSlowPath(): Any? {
        while (true) { // loop until there is an available item to retrieve.
            queueCapacityState.queueHasItemOrSuspend() // suspend until queue has an item ready for retrieval.
            workers.withPermit {
                if (isEmpty) return@withPermit // continue, suspend again until there is an available item.
                val job = removeFirstOrNull()
                    ?: return@withPermit // continue, suspend again until there is an available item.
                // set state to `0` only if this item was not the last one.
                if (hasCapacity) queueCapacityState.signalQueueIsNotEmpty()
                return job.invoke(WorkerScope).await()
            }
        }
    }
}

fun emptyArrayQueue(): ArrayQueue = ArrayQueue()

/**
 * Represents whether queue is empty or not. Technically, it is a wrapper above a semaphore with single permit.
 *  Its main purpose is to help suspend consumers who try to dequeue from queue when is empty, until next insertion.
 */
private class QueueCapacityState {
    /*
     * This semaphore represents whether there is any item in queue for retrieval.
     *
     * 0 -> Empty queue
     * 1 -> Not empty queue
     *
     *                         States
     *
     *  queue is empty     |    insertion of at least one item   |  insertion of items    |    queue is empty
     *  | ---- 0 ---- |   -->   | ---- 1 ---- |                 -->  | ---- 1 ---- |     -->   | ---- 0 ---- |
     */
    private val state = Semaphore(
        1,
        // Queue's initial state is empty.
        1
    )

    // A separate tracker for permits, to allow atomic operations on it, aka see signalQueueIsNotEmpty().
    private val trackedPermits = atomic(0)

    suspend fun queueHasItemOrSuspend() {
        state.acquire()
        trackedPermits.decrementAndGet()
    }

    fun signalQueueIsEmpty() {
        // if tryAcquire() fails, it means state is already to `0`.
        if (state.tryAcquire()) trackedPermits.decrementAndGet()
    }

    fun signalQueueIsNotEmpty() {
        if (trackedPermits.value != 0) return // no need to update state.
        // try update first `trackedPermits` to avoid race conditions (two threads read variable at the same time),
        // then update state. If update fails, other thread was faster. In that case we just return, since goal was
        // already achieved (set state to 0).
        if (!trackedPermits.compareAndSet(0, 1)) return
        state.release() // safe to set state to `0`.
    }
}