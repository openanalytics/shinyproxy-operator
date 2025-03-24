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
package eu.openanalytics.shinyproxyoperator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists

class FileManager {

    fun writeFile(path: Path, content: String, strictPermissions: Boolean = true) = writeFile(path, content.toByteArray(), strictPermissions)

    fun writeFile(path: Path, content: ByteArray, strictPermissions: Boolean = true) {
        Files.write(
            path,
            content
        )
        if (strictPermissions) {
            Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        }
    }

    /**
     * @return whether the file content has changed
     */
    suspend fun writeFromResource(source: String, destination: Path, strictPermissions: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val contents = this::class.java.getResourceAsStream(source)?.bufferedReader()?.readText() ?: error("Missing resource: $source")
        if (destination.exists() && destination.toFile().bufferedReader().readText() == contents) {
            return@withContext false
        }
        writeFile(destination, contents, strictPermissions)
        return@withContext true
    }

    /**
     * @return whether the file content has changed
     */
    suspend fun writeFromResources(dir: String, sources: List<String>, destination: Path, strictPermissions: Boolean = true): Boolean {
        var changed = false
        for (source in sources) {
            if (writeFromResource(dir + source, destination.resolve(source), strictPermissions)) {
                changed = true
            }
        }
        return changed
    }

    fun createDirectories(path: Path) {
        if (!Files.exists(path)) {
            Files.createDirectories(path)
        }
    }

    @OptIn(ExperimentalPathApi::class)
    fun removeDirectory(path: Path) {
        path.deleteRecursively()
    }

}
