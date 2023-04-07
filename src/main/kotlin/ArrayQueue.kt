package asynqueueproblem

import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
    private val array: Array<AtomicRef<QueueJob?>> = Array(sizeOfArray) { atomic(null) }
    private val _size = atomic(0)

    // Represent workers as semaphores to allow suspension when there is no worker available, instead of looping
    // continuously for free worker.
    private val workers = Semaphore(numberOfWorkers)

    private val hasCapacity: Boolean get() = _size.value < array.size

    init {
        require(numberOfWorkers > 0) { "number of workers cannot be 0 or negative, but got:$numberOfWorkers" }
        if (initialValue != null) {
            array[0] = atomic(initialValue)
            _size.incrementAndGet()
        }
    }

    val size: Int get() = _size.value
    val isEmpty: Boolean get() = size == 0

    // Returns false if array is full else true.
    // Suspends when there is not an available worker.
    suspend fun tryEnqueue(value: QueueJob): Boolean {
        workers.withPermit {
            if (!hasCapacity) return false
            // find next free index
            var index = 0
            var freeIndex = array[index]
            while (freeIndex.value != null) {
                freeIndex = array[++index]
            }
            if (!freeIndex.compareAndSet(null, value)) return tryEnqueue(value) // at this point, there is
            // a chance that the array is already filled up. That's why we cannot simply look for the next free spot
            // without checking again if there is enough space.
            return true // enqueued job
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
        workers.withPermit {
            if (isEmpty) return Failure
            val job = array.first().getAndUpdate {
                if (it == null) return dequeue() // head is already removed, therefore we can assume that array is under
                // restructuring. Nonetheless, we trigger again dequeue() to give up the current worker, and check again
                // if array is empty.
                null // remove head
            }
            restructureArray() // restructure array before processing
            return Success(job!!.invoke(WorkerScope).await())
        }
    }

    private fun restructureArray() {
        for ((index, item) in array.withIndex()) {
            if (index == 0 && item.value != null) return // array is already restructured.
            if (index == array.size - 1) return // last item is left as null.
            if (item.value != null) { //TODO: check if this is scenario is possible for big numbers
                // in this case another worker as already restructured this position.
                continue // But, it does not affect us directly, we can simply skip this cell.
            }
            item.compareAndSet(null, array[index + 1].value) // if false we skip the operation either way, since
            // is the last operation in loop.
        }
    }
}

fun emptyArrayQueue(): ArrayQueue = ArrayQueue()