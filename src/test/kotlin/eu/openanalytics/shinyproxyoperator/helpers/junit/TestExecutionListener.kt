/*
 * ShinyProxy-Operator
 *
 * Copyright (C) 2021-2025 Open Analytics
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
package eu.openanalytics.shinyproxyoperator.helpers.junit

import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import java.io.PrintWriter

class TestExecutionListener : SummaryGeneratingListener() {

    private var numTests: Long = 0
    private var currentTest: Long = 0

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            if (summary != null) {
                summary.printTo(PrintWriter(System.out))
            }
        })
    }

    override fun testPlanExecutionStarted(testPlan: TestPlan) {
        super.testPlanExecutionStarted(testPlan)

        numTests = testPlan.countTestIdentifiers { it.isTest || (it.isContainer && it.uniqueIdObject.lastSegment.type == "test-template")  }
    }

    override fun executionSkipped(testIdentifier: TestIdentifier?, reason: String?) {
        super.executionSkipped(testIdentifier, reason)
        if (testIdentifier == null || reason == null || !testIdentifier.isTest) return

        updateCount(testIdentifier)

        println()
        println("\t\t--> Skipping test [$currentTest/$numTests] \"${testIdentifier.displayName}\"")
        println()
    }

    override fun executionStarted(testIdentifier: TestIdentifier?) {
        super.executionStarted(testIdentifier)
        if (testIdentifier == null || !testIdentifier.isTest) {
            return
        }

        updateCount(testIdentifier)

        println()
        println("\t\t--> Started test [$currentTest/$numTests] \"${testIdentifier.displayName}\"")
        println()
    }

    override fun executionFinished(testIdentifier: TestIdentifier?, testExecutionResult: TestExecutionResult?) {
        super.executionFinished(testIdentifier, testExecutionResult)
        if (testIdentifier == null || testExecutionResult == null || !testIdentifier.isTest) return

        println()
        println("\t\t--> Finished test [$currentTest/$numTests] \"${testIdentifier.displayName}\": $testExecutionResult")
        if (testExecutionResult.throwable.isPresent) {
            println()
            print("\t\t--> ")
            println(testExecutionResult.throwable.get().stackTraceToString())
        }
        println()
    }

    private fun updateCount(testIdentifier: TestIdentifier) {
        if (testIdentifier.uniqueIdObject?.lastSegment?.type == "test-template-invocation") {
            val c = testIdentifier.uniqueIdObject?.lastSegment?.value?.replace("#", "")?.toIntOrNull() ?: return
            if (c > 1) {
                // update total count if it's a template (instances of templates are not included in the initial total)
                numTests++
            }
        }

        currentTest++
    }

}
