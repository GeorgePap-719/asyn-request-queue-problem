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
    private fun AtomicInt.moveIndexForward() {
        update { (it + 1).mod(array.size) }
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
            try {
                while (true) {
                    if (isFull) return false
                    if (array[tail.value].compareAndSet(null, value)) return true
                }
            } finally {
                tail.moveIndexForward()
                _size.incrementAndGet()
                if (queueAvailableForDequeue.availablePermits == 0) {
                    // This needed in order to allow consumers suspend when they try to dequeue from queue when is empty.
                    queueAvailableForDequeue.release() // signal waiters that queue is ready for dequeue.
                }
            }
        }
    }

    // Returns value or Failure if queue is empty.
    // Suspends when there is not an available worker.
    suspend fun dequeue(): QueueResult {
        /*
         * Implementation notes
         *
         * We always dequeue only head position in array, and then we restructure the array to
         * occupy the head position with the next in line item. This is done, in order to enforce the FIFO order in
         * this structure. While restructuring an array can be a time-consuming operation (for big arrays), we only
         * need the head position filled up to dequeue an object. This means that a worker is able to retrieve a head
         * while another worker has not finished restructuring the array.
         */
//        workers.withPermit {
//            if (isEmpty) return Failure
//            val job = array.first().getAndUpdate {
//                if (it == null) return@getAndUpdate null // head is already removed, therefore we can assume that array is under
//                // restructuring. Nonetheless, we trigger again dequeue() to give up the current worker, and check again
//                // if array is empty.
//                null // remove head
//            } ?: return dequeue()
//            restructureArray() // restructure array before processing
//            _size.decrementAndGet()
//            return Success(job.invoke(WorkerScope).await())
//        }

        val isQueueEmpty = workers.withPermit {
            if (isEmpty) return@withPermit true
            while (true) {
                val job = array[head.value].get() ?: continue
                head.moveIndexForward()
                _size.decrementAndGet()
                return Success(job.invoke(WorkerScope).await())
            }
        }
        if (isQueueEmpty as Boolean) {
            TODO("not yet impl")
        }
    }

    @Suppress("ClassName")
    private object EMPTY_QUEUE
}

fun emptyArrayQueue(): ArrayQueue = ArrayQueue()