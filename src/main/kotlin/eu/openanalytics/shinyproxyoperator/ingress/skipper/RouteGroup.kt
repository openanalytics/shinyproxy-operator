/**
 * ShinyProxy-Operator
 *
 * Copyright (C) 2021 Open Analytics
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
package eu.openanalytics.shinyproxyoperator.ingress.skipper

import io.fabric8.kubernetes.api.model.Namespaced
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.Group
import io.fabric8.kubernetes.model.annotation.Version

class RouteGroupStatus()

data class Backend(val name: String, val type: String)
data class BackendName(val backendName: String)
data class Route(val pathSubtree: String, val filters: List<String>, val backends: List<BackendName>)
data class RouteGroupSpec(val hosts: List<String>, val backends: List<Backend>, val defaultBackends: List<BackendName>, val routes: List<Route>)

// TODO create tests
@Version("v1")
@Group("zalando.org")
class RouteGroup: CustomResource<RouteGroupSpec, RouteGroupStatus>(), Namespaced {

}

