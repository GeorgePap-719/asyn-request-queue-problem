package asynqueueproblem

import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay

/**
 * Queue implementation based on linkedList.
 */
class Queue(
    initialValue: QueueJob? = null,
    /**
     * Sets the maximum available workers to process a job concurrently.
     */
    private val numberOfWorkers: Int = 3
) {
    private val activeWorkers = atomic(0)
    private val head = atomic<Node?>(null)

    // Returns the physical tail in linkedList. Always check if queue is empty before invoking this, as
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
        if (initialValue != null) {
            val node: Node = Node(initialValue)
            head.compareAndSet(null, node)
            _size.incrementAndGet()
        }
    }

    val size: Int get() = _size.value

    fun enqueue(value: QueueJob) {
        try {
            val newNode = Node(value)
            if (head.value == null) {
                val result = head.compareAndSet(null, newNode) // if queue is empty update head
                if (!result) updateTail(newNode) // fallback: head is occupied, proceed to add node in tail
            } else {
                updateTail(newNode) // else update tail
            }
        } finally {
            _size.incrementAndGet()
        }
    }

    private fun updateTail(newNode: Node) {
        while (true) if (tail.next.compareAndSet(null, newNode)) return
    }

    private val invocations = atomic(0)

    // Returns value or Failure if queue is empty
    suspend fun dequeue(): QueueResult {
//        val incrementAndGet = invocations.incrementAndGet()
//        println("invocations: $incrementAndGet")
        if (isEmpty) return Failure
        while (!hasFreeWorker()) {
            println("waiting for an available worker, active workers:${activeWorkers.value}")
            delay(100)
        }
        activeWorkers.incrementAndGet()
        try {
            val job = removeOrReplaceHeadAndReturnCurrentJob()
            return Success(job.invoke(WorkerScope).await())
        } finally {
            _size.decrementAndGet()
            activeWorkers.decrementAndGet() // release worker
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

    private fun hasFreeWorker(): Boolean = activeWorkers.value != numberOfWorkers

    private class Node(
        val job: QueueJob,
        // Pointer to the next node in linked list.
        val next: AtomicRef<Node?> = atomic(null)
    ) {
        // Checks if this node is the physical tail of the linked list.
        val isTail: Boolean get() = next.value == null
    }

    private val Node.isNotTail: Boolean get() = !isTail
}

fun queueWithInitialValue(value: suspend CoroutineScope.() -> Deferred<Any?>): Queue {
    return Queue(value)
}

sealed class QueueResult
data class Success(val value: Any?) : QueueResult()
object Failure : QueueResult()

typealias QueueJob = suspend CoroutineScope.() -> Deferred<Any?>

val WorkerScope = CoroutineScope(Dispatchers.IO)