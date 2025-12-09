# ShinyProxy Operator

Easily run ShinyProxy on a Docker host or on a Kubernetes cluster.

**(c) Copyright Open Analytics NV, 2020-2025 - Apache License 2.0**

## Why?

See the [Features](https://shinyproxy.io/documentation/shinyproxy-operator/features/)
page in the documentation to understand why this is so great.

## Docs

See the [website](https://shinyproxy.io/documentation/shinyproxy-operator/kubernetes/) for all documentation.

### Docker Mode: Passing Environment Variables

When using the operator in Docker mode, you can pass environment variables from the operator's environment to ShinyProxy containers. This is useful for injecting secrets without storing them in plain text. See [Environment Variables for Docker Mode](docs/environment-variables-docker.md) for details.

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

This project requires JDK 21.

## CI/CD

This repository includes automated GitHub Actions workflows for building and deploying the ShinyProxy Operator.

### Docker Image Build and Push

A workflow automatically builds the operator JAR, creates a Docker image, and pushes it to a Container Registry. See [docs/CI-CD.md](docs/CI-CD.md) for detailed documentation on:

- Workflow configuration
- Required secrets setup
- Docker image tags
- Manual workflow dispatch
- Local development replication
