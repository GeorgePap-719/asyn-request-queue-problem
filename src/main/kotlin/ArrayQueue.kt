package asynqueueproblem

import kotlinx.atomicfu.AtomicInt
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicReference

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

    /*
     * This semaphore represents whether there is any item in queue for retrieval. Its main purpose is to help suspend
     * consumers who try to dequeue from queue when is empty. Consumers are suspended until next insertion.
     *
     * 0 -> Empty queue
     * 1 -> Not empty queue
     *
     *                         States
     *
     *  queue is empty     |    insertion of at least one item   |  insertion of items    |    queue is empty
     *  | ---- 0 ---- |   -->   | ---- 1 ---- |                 -->  | ---- 1 ---- |     -->   | ---- 0 ---- |
     */
    private val queueAvailableForDequeue = Semaphore(
        1,
        // Queue's initial state is empty,
        1
    )

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
            queueAvailableForDequeue.release() // release sem, since queue is not empty.
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
                    if (queueAvailableForDequeue.availablePermits == 0) { // this is not concurrent-safe operation.
                        // This needed in order to allow consumers suspend when they try to dequeue from queue when is empty.
                        println("permits:${queueAvailableForDequeue.availablePermits}")
                        queueAvailableForDequeue.release() // signal waiters that queue is ready for dequeue.
                    }
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
                // if queue is empty cause of this `dequeue` operation, then acquire permit (signal queue is empty), if
                // there is one.
                if (isEmpty) queueAvailableForDequeue.tryAcquire()
                return job.invoke(WorkerScope).await()
            }
        }
        // queue is empty at this point.
        return dequeueSlowPath()
    }

    private suspend fun dequeueSlowPath(): Any? {
        while (true) { // loop until there is an available item to retrieve.
            queueAvailableForDequeue.acquire() // suspend until queue has an item ready for retrieval.
            workers.withPermit {
                if (isEmpty) return@withPermit // continue, suspend again until there is an available item.
                val job = removeFirstOrNull()
                    ?: return@withPermit // continue, suspend again until there is an available item.
                if (hasCapacity) queueAvailableForDequeue.release() // release sem only if this item was not the last one.
                return job.invoke(WorkerScope).await()
            }
        }
    }
}

fun emptyArrayQueue(): ArrayQueue = ArrayQueue()