package eu.openanalytics.shinyproxyoperator.crd

import io.fabric8.kubernetes.api.builder.Function
import io.fabric8.kubernetes.client.CustomResourceDoneable

class DoneableShinyProxy(resource: ShinyProxy?, function: Function<ShinyProxy?, ShinyProxy?>?) : CustomResourceDoneable<ShinyProxy?>(resource, function)
