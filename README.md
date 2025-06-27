# ShinyProxy Operator

Easily run ShinyProxy on a Docker host or on a Kubernetes cluster.

**(c) Copyright Open Analytics NV, 2020-2025 - Apache License 2.0**

## Why?

See the [Features](https://shinyproxy.io/documentation/shinyproxy-operator/features/)
page in the documentation to understand why this is so great.

## Docs

See the [website](https://shinyproxy.io/documentation/shinyproxy-operator/kubernetes/) for all documentation.

## Support

See the [website](https://shinyproxy.io/support/) on how to get support.

## Announcements

Subscribe on the [community forum](https://support.openanalytics.eu/c/shinyproxy/shinyproxy-announcements/10) for announcements of new releases and features.

## Building from source

Clone this repository and run

```bash
mvn -U clean install
```

The build will result in a single `.jar` file:
`target/target/shinyproxy-operator-jar-with-dependencies.jar`.

## Java Version

This project requires JDK 17.
