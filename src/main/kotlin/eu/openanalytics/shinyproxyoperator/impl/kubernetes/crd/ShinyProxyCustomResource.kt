/*
 * ShinyProxy-Operator
 *
 * Copyright (C) 2021-2024 Open Analytics
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
package eu.openanalytics.shinyproxyoperator.impl.kubernetes.crd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.JsonNode
import eu.openanalytics.shinyproxyoperator.convertToYamlString
import eu.openanalytics.shinyproxyoperator.getSubPath
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyInstance
import eu.openanalytics.shinyproxyoperator.model.ShinyProxyStatus
import eu.openanalytics.shinyproxyoperator.sha1
import io.fabric8.kubernetes.api.model.Namespaced
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.Group
import io.fabric8.kubernetes.model.annotation.Kind
import io.fabric8.kubernetes.model.annotation.Version


@Version("v1")
@Group("openanalytics.eu")
@Kind("ShinyProxy")
class ShinyProxyCustomResource : CustomResource<JsonNode, ShinyProxyStatusCustomResource>(), Namespaced {

    // TODO realmId has changed

    @JsonIgnore
    fun getSpStatus(): ShinyProxyStatus {
        val instances = status.instances.map { ShinyProxyInstance(metadata.name, metadata.namespace, realmId, it.hashOfSpec, it.isLatestInstance, it.revision) }
        return ShinyProxyStatus(realmId, hashOfCurrentSpec, instances)
    }

    @JsonIgnore
    fun setSpStatus(shinyProxyStatus: ShinyProxyStatus) {
        status = ShinyProxyStatusCustomResource(shinyProxyStatus.instances.map { ShinyProxyInstanceResource(it.hashOfSpec, it.isLatestInstance, it.revision) })
    }

    override fun initStatus(): ShinyProxyStatusCustomResource {
        return ShinyProxyStatusCustomResource()
    }

    @get:JsonIgnore
    val specAsYaml: String by lazy {
        return@lazy spec.convertToYamlString()
    }

    @get:JsonIgnore
    val hashOfCurrentSpec: String by lazy {
        return@lazy specAsYaml.sha1()
    }

    @get:JsonIgnore
    val realmId: String by lazy {
        return@lazy "${metadata.namespace}-${metadata.name}"
    }

    @get:JsonIgnore
    val subPath: String by lazy {
        return@lazy spec.getSubPath()
    }

    override fun setKind(kind: String) {
        // prevent fabric8 form logging a message here
    }

    override fun setApiVersion(version: String) {
        // prevent fabric8 form logging a message here
    }

}
