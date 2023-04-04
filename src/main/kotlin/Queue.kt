package asynqueueproblem

import kotlinx.coroutines.*

// Queue is => FIFO

/**
 * Queue implementation based on linkedList.
 */
class Queue(initialValue: QueueJob? = null) {
    private var activeWorkers = 0

    // Returns head or null if queue is empty
    private val head: QueueNode
        get() {
            checkNotNull(tail) { "queue is empty" }
            var nextNode: QueueNode = tail!!
            while (nextNode.next != null) {
                nextNode = nextNode.next!!
            }
            return nextNode
        }
    private var tail: QueueNode? = null

    //
    private var _size = 0
    private val isEmpty: Boolean get() = _size == 0

    init {
        if (initialValue != null) {
            tail = QueueNode(initialValue)
            _size++
        }
    }

    val size get() = _size

    fun enqueue(value: QueueJob) {
        try {
            val newNode = QueueNode(value)
            if (tail == null) {
                tail = newNode
                return
            }
            // add new node in tail position
            setNextNode(newNode)
        } finally {
            _size++
        }
    }

    private fun setNextNode(newNode: QueueNode) {
        val oldTail = tail
        tail = newNode
        tail!!.next = oldTail
    }

    /**
     * Blocking dequeue.
     */
    suspend fun dequeue(): Any? {
        println("trying to dequeue")
        if (isEmpty) {
            println("isEmpty with size:$size")
            return Unit // if queue is empty break
        }
        while (!hasFreeWorker()) {
            println("waiting for an available worker")
            delay(100)
        }
        activeWorkers++
        try {
            println("poping head")
            val job = head.value
            findHeadAndRemove()
            return job.invoke(WorkerScope).await()
        } finally {
            activeWorkers-- // release worker
            _size--
        }
    }

    private fun findHeadAndRemove() {
        checkNotNull(tail)
        var nextNode: QueueNode = tail!!
        var prevNode: QueueNode = tail!!
        while (nextNode.next != null) {
            prevNode = nextNode
            nextNode = nextNode.next!!
        }
        prevNode.next = null // remove head
    }

    private fun hasFreeWorker(): Boolean = activeWorkers != MAX_WORKERS

    private class QueueNode(var value: QueueJob, var next: QueueNode? = null)

    companion object {
        internal const val MAX_WORKERS = 3
    }
}

fun queueWithInitialValue(value: suspend CoroutineScope.() -> Deferred<Any?>): Queue {
    return Queue(value)
}

typealias QueueJob = suspend CoroutineScope.() -> Deferred<Any?>

@Suppress("PrivatePropertyName")
private val WorkerScope = CoroutineScope(Dispatchers.IO)