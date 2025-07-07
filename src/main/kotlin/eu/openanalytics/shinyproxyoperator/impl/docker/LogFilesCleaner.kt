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
package eu.openanalytics.shinyproxyoperator.impl.docker

import eu.openanalytics.shinyproxyoperator.FileManager
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.time.Instant
import java.util.*
import kotlin.concurrent.timer

class LogFilesCleaner(private val path: Path, private val fileManager: FileManager, private val dockerActions: DockerActions) {

    private val logger = KotlinLogging.logger {}
    private var timer: Timer? = null

    fun init() {
        timer = timer(period = 60 * 60_000L, initialDelay = 60_000L) {
            runBlocking {
                try {
                    cleanupDirectory(path, Duration.ofDays(7))
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to cleanup logs directory" }
                }
            }
        }
    }

    private fun cleanupDirectory(path: Path, maxAge: Duration) {
        val now = Instant.now()
        Files.walkFileTree(path, object : FileVisitor<Path> {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (isUsedByContainer(dir)) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                try {
                    val age = Duration.between(attrs.lastModifiedTime().toInstant(), now)
                    if (age > maxAge) {
                        logger.debug { "Deleting: $file" }
                        Files.delete(file)
                    }
                } catch (e: IOException) {
                    logger.warn(e) { "Failed to delete file: $file" }
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                try {
                    if (fileManager.isDirectoryEmpty(dir)) {
                        logger.debug { "Deleting: $dir" }
                        Files.delete(dir)
                    }
                } catch (e: IOException) {
                    logger.warn(e) { "Failed to delete empty directory: $dir" }
                }
                return FileVisitResult.CONTINUE
            }
        })
    }

    /**
     * Checks if the given path is currently in use as a container.
     * Assumes the directory name starts with 'sp-' and corresponds to the name of the container.
     */
    private fun isUsedByContainer(path: Path): Boolean {
        if (path.fileName.toString().startsWith("sp-")) {
            try {
                if (dockerActions.getContainerByName(path.fileName.toString()) != null) {
                    return true
                }
            } catch (_: Exception) {

            }
        }
        return false
    }


    fun stop() {
        timer?.cancel()
    }

}
