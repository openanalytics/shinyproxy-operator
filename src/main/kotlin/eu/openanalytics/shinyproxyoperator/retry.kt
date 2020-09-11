package eu.openanalytics.shinyproxyoperator

import kotlinx.coroutines.delay
import java.lang.RuntimeException
import java.util.function.IntPredicate;

suspend fun retry(tries: Int, waitTime: Long, job: IntPredicate): Boolean {
    return retry(tries, waitTime, false, job)
}

suspend fun retry(tries: Int, waitTime: Long, retryOnException: Boolean, job: IntPredicate): Boolean {
    var retVal = false;
    var exception: RuntimeException? = null
    for (currentTry in 1 until tries) {
        try {
            if (job.test(currentTry)) {
                retVal = true;
                exception = null;
                break;
            }
        } catch (e: RuntimeException) {
            if (retryOnException) exception = e;
            else throw e;
        }
        delay(waitTime)
    }
    if (exception == null) {
        return retVal
    } else {
        throw exception
    }
}

