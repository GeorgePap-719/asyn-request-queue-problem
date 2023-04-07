package asynqueueproblem

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred

typealias QueueJob = suspend CoroutineScope.() -> Deferred<Any?>