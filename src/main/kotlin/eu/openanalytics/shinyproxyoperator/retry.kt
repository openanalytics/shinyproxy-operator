/**
 * ShinyProxy-Operator
 *
 * Copyright (C) 2021-2022 Open Analytics
 *
 * ===========================================================================
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Apache License as published by
 * The Apache Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Apache License for more details.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/>
 */
package eu.openanalytics.shinyproxyoperator

import kotlinx.coroutines.delay
import java.util.function.IntPredicate

suspend fun retry(tries: Int, waitTime: Long, job: IntPredicate): Boolean {
    return retry(tries, waitTime, false, job)
}

suspend fun retry(tries: Int, waitTime: Long, retryOnException: Boolean, job: IntPredicate): Boolean {
    var retVal = false
    var exception: RuntimeException? = null
    for (currentTry in 1 until tries) {
        try {
            if (job.test(currentTry)) {
                retVal = true
                exception = null
                break
            }
        } catch (e: RuntimeException) {
            if (retryOnException) exception = e
            else throw e
        }
        delay(waitTime)
    }
    if (exception == null) {
        return retVal
    } else {
        throw exception
    }
}

