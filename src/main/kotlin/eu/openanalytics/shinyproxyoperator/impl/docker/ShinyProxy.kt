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

import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import eu.openanalytics.shinyproxyoperator.model.ShinyProxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.PathWalkOption
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.walk

fun ShinyProxy.getCaddyTlsCertFile(): Path? {
    if (getSpec().get("caddyTlsCertFile")?.isTextual == true) {
        return Path.of(getSpec().get("caddyTlsCertFile").textValue())
    }
    return null
}

fun ShinyProxy.getCaddyTlsKeyFile(): Path? {
    if (getSpec().get("caddyTlsKeyFile")?.isTextual == true) {
        return Path.of(getSpec().get("caddyTlsKeyFile").textValue())
    }
    return null
}

fun ShinyProxy.getCaddyRedirects(): List<CaddyRedirect> {
    if (getSpec().get("caddyRedirects")?.isArray == true) {
        return jacksonObjectMapper().convertValue(getSpec().get("caddyRedirects"))
    }
    return listOf()
}

fun ShinyProxy.getAdditionalConfigFiles(): List<Path> {
    if (getSpec().get("additionalConfigFiles")?.isArray == true) {
        return getSpec().get("additionalConfigFiles").elements().asSequence().map { Path.of(it.textValue()) }.toList()
    }
    if (getSpec().get("additional-config-files")?.isArray == true) {
        return getSpec().get("additional-config-files").elements().asSequence().map { Path.of(it.textValue()) }.toList()
    }
    return listOf()
}

fun ShinyProxy.getCaBundleFile(inputDir: Path): Path {
    if (getSpec().get("caBundleFile")?.isTextual == true) {
        return Path.of(getSpec().get("caBundleFile").textValue())
    }
    if (getSpec().get("ca-bundle-file")?.isTextual == true) {
        return Path.of(getSpec().get("ca-bundle-file").textValue())
    }
    return inputDir.resolve("ca-bundle.crt").absolute()
}

private fun ShinyProxy.isTemplateModified(inputDir: Path, lastModified: Long): Pair<Boolean, Path?> {
    val source = getTemplateSource(inputDir) ?: return Pair(false, null)

    source.walk(PathWalkOption.INCLUDE_DIRECTORIES).forEach { path ->
        if (path.getLastModifiedTime().toMillis() > lastModified) {
            return Pair(true, path)
        }
    }

    return Pair(false, null)
}

fun ShinyProxy.isReferencedFileMoreRecent(inputDir: Path, lastModified: Long): Pair<Boolean, Path?> {
    val files = listOf(getCaddyTlsCertFile(), getCaddyTlsKeyFile(), getCaBundleFile(inputDir)) + getAdditionalConfigFiles()
    for (file in files) {
        if (file == null || !file.exists() || !file.isRegularFile()) {
            continue
        }
        if (file.getLastModifiedTime().toMillis() > lastModified) {
            return Pair(true, file)
        }
    }
    return isTemplateModified(inputDir, lastModified)
}

fun ShinyProxy.getTemplateSource(inputDir: Path): Path? {
    val source = inputDir.resolve("templates").resolve(name)
    if (Files.exists(source) && Files.isDirectory(source)) {
        return source
    }
    val source2 = inputDir.resolve("templates").resolve(realmId)
    if (Files.exists(source2) && Files.isDirectory(source2)) {
        return source2
    }
    return null
}

data class CaddyRedirect(val from: String, val to: String, val statusCode: Int = 302)
