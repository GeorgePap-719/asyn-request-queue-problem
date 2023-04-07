package asynqueueproblem

sealed class QueueResult
data class Success(val value: Any?) : QueueResult()
object Failure : QueueResult()