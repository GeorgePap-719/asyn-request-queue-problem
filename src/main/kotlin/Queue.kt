package asynqueueproblem

import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * A concurrent queue implementation using a linked list to store items.
 *
 * The core idea is that each item in the queue represents a [job][QueueJob], which needs to be processed.
 * Each job can only be processed by a free "worker", if there is one available, otherwise it suspends until a worker
 * becomes free. By extension, the number of workers represent the queue's parallelism potential.
 *
 * Important notes:
 * * Only [dequeue] operation is suspended, since only then we process jobs, and because jobs are represented as
 * [Deferred] values.
 * * While queue is a FIFO data structure, it is possible that one job might finish earlier from another which has
 * started beforehand. Only the sequence of job execution can be guaranteed.
 */
class Queue(
    initialValue: QueueJob? = null,
    /**
     * Sets the maximum available workers to process jobs concurrently.
     */
    private val numberOfWorkers: Int = 3
) {
    private val head = atomic<Node?>(null)

    // Represent workers as semaphores to allow suspension when there is no worker available, instead of looping
    // continuously for free worker.
    private val workers = Semaphore(numberOfWorkers)

    // Returns the physical tail in linked list. Always check if queue is empty before invoking this, as
    // it throws IllegalStateException.
    private val tail: Node
        get() {
            check(!isEmpty) { "queue is empty" }
            var nextNode: Node = head.value!!
            while (!nextNode.isTail) {
                nextNode = nextNode.next.value!!
            }
            return nextNode
        }

    private val _size = atomic(0)
    private val isEmpty: Boolean get() = head.value == null

    init {
        require(numberOfWorkers > 0) { "number of workers cannot be 0 or negative, but got:$numberOfWorkers" }
        if (initialValue != null) {
            head.compareAndSet(null, Node(initialValue))
            _size.incrementAndGet()
        }
    }

    val size: Int get() = _size.value

    fun enqueue(value: QueueJob) {
        try {
            val newNode = Node(value)
            if (head.value == null) {
                val result = head.compareAndSet(null, newNode) // if queue is empty update head
                if (!result) tail.setNext(newNode) // fallback: head is occupied, proceed to add node in tail
            } else {
                tail.setNext(newNode) // else update tail
            }
        } finally {
            _size.incrementAndGet()
        }
    }

    // Returns value or Failure if queue is empty.
    // Suspends when there is not an available worker.
    suspend fun dequeue(): QueueResult {
        if (isEmpty) return Failure
        return if (workers.tryAcquire()) {
            workers.useAcquiredPermit { dequeueImpl() }
        } else {
            // for debug output, but it is not guaranteed that it will represent the actual state of availablePermits.
            println("waiting for an available worker, available workers:${workers.availablePermits}")
            workers.withPermit { dequeueImpl() }
        }
    }

    private suspend fun dequeueImpl(): QueueResult {
        try {
            val job = removeOrReplaceHeadAndReturnCurrentJob()
            return Success(job.invoke(WorkerScope).await())
        } finally {
            _size.decrementAndGet()
        }
    }

    private fun removeOrReplaceHeadAndReturnCurrentJob(): QueueJob {
        return if (head.value!!.isTail) { // remove head
            val cur = head.value
            val update = head.compareAndSet(cur, null)
            if (!update) return removeOrReplaceHeadAndReturnCurrentJob() // check again whether head `isTail` or not.
            return cur!!.job
        } else { // replace head with next in line node.
            val cur = head.value!!
            // In this case, we cannot loop on value, since the head might become a tail on any modification.
            if (!head.compareAndSet(cur, cur.next.value)) return removeOrReplaceHeadAndReturnCurrentJob()
            cur.job
        }
    }

    private class Node(
        val job: QueueJob,
        @Suppress("LocalVariableName") _next: Node? = null
    ) {
        // Pointer to the next node in linked list.
        val next: AtomicRef<Node?> = atomic(_next)

        // Checks if this node is the physical tail of the linked list.
        val isTail: Boolean get() = next.value == null

        fun setNext(newNode: Node) {
            while (true) if (next.compareAndSet(null, newNode)) return
        }
    }
}

fun queueWithInitialValue(value: suspend CoroutineScope.() -> Deferred<Any?>): Queue {
    return Queue(value)
}

fun emptyQueue(): Queue = Queue()

sealed class QueueResult
data class Success(val value: Any?) : QueueResult()
object Failure : QueueResult()

typealias QueueJob = suspend CoroutineScope.() -> Deferred<Any?>

val WorkerScope = CoroutineScope(Dispatchers.IO)

// assumes permit is already granted.
@OptIn(ExperimentalContracts::class)
private inline fun <T> Semaphore.useAcquiredPermit(action: () -> T): T {
    contract {
        callsInPlace(action, InvocationKind.EXACTLY_ONCE)
    }
    try {
        return action()
    } finally {
        release()
    }
}
