package eu.openanalytics.shinyproxyoperator.controller

enum class ShinyProxyEventType {
    ADD,
    UPDATE_SPEC,
    DELETE,
    RECONCILE,
    CHECK_OBSOLETE_INSTANCES
}