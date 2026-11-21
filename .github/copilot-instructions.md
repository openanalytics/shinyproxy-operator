# ShinyProxy Operator - Copilot Instructions

## Project Overview

ShinyProxy Operator is a Kubernetes/Docker operator for managing ShinyProxy deployments. It's written in Kotlin and uses coroutines for asynchronous operations.

## Technology Stack

- **Language**: Kotlin 2.1.10 with language version 2.1
- **JDK**: Java 21 (required)
- **Build Tool**: Maven 3.x
- **Container Orchestration**: Kubernetes (via Fabric8 client) and Docker
- **Logging**: kotlin-logging with Log4j 2
- **Testing**: JUnit 5 (Jupiter) for unit and integration tests
- **Async**: Kotlin Coroutines

## Build and Test Commands

### Building the Project
```bash
devbox run mvn -U clean install -DskipTests
```

The build produces: `target/shinyproxy-operator-jar-with-dependencies.jar`

### Running Tests
```bash
devbox run mvn test
```

Tests require:
- Docker network: `sp-shared-network`
- Docker plugin: `grafana/loki-docker-driver:3.2.1`
- Environment variable: `SPO_DOCKER_GID` (group ID for docker group)

### License Header Management
```bash
devbox run mvn validate license:format
```

Automatically updates copyright headers. Year updates are handled automatically - don't change year manually.

## Code Style and Conventions

### File Structure
- Source code: `src/main/kotlin/`
- Test code: `src/test/kotlin/`
- Package: `eu.openanalytics.shinyproxyoperator`

### Formatting Guidelines
- **Indentation**: 4 spaces (not tabs)
- **Max line length**: 120 characters
- **Charset**: UTF-8
- **Line endings**: LF (Unix-style)
- **Trailing whitespace**: Remove
- **Final newline**: Always insert

### Kotlin Conventions
- Use `object` for singletons (e.g., `LabelFactory`)
- Use `const val` for compile-time constants
- Prefer `when` expressions over if-else chains
- Use `kotlinx.coroutines` for async operations
- Use `suspend` functions appropriately
- Use kotlin-logging's KotlinLogging for logging: `private val logger = KotlinLogging.logger {}`

### Copyright Headers
Every Kotlin source file must include the Apache License 2.0 header. Use the template in `LICENSE_HEADER`. The license plugin handles year updates automatically.

Example header structure:
```kotlin
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
```

## Architecture

### Core Components
- **Operator Interface**: `IOperator` - main entry point
- **Orchestrators**: Platform-specific implementations
  - `KubernetesOperator`: For Kubernetes deployments
  - `DockerOperator`: For Docker deployments
- **Event System**: `ShinyProxyEvent`, `ShinyProxyEventType`, `IEventController`
- **Model**: `ShinyProxy`, `ShinyProxyInstance`, `ShinyProxyStatus`

### Configuration
- Configuration via environment variables (see `Config` class)
- Key variable: `SPO_ORCHESTRATOR` - determines platform (kubernetes/docker)

### Implementations
- `src/main/kotlin/eu/openanalytics/shinyproxyoperator/impl/kubernetes/` - Kubernetes-specific code
- `src/main/kotlin/eu/openanalytics/shinyproxyoperator/impl/docker/` - Docker-specific code

## Testing

### Test Structure
- Integration tests: `MainIntegrationTest.kt` in both impl/kubernetes and impl/docker
- Helper classes in `src/test/kotlin/eu/openanalytics/shinyproxyoperator/helpers/`
- Test base classes provide setup/teardown functionality

### Testing Patterns
- Use `@Test` annotation from JUnit 5
- Use Kotlin test assertions: `assertEquals`, `assertTrue`, `assertFalse`, `assertNotNull`, `assertNull`
- Integration tests use `setup()` helper with closures
- Tests use coroutines with `withTimeout` for async operations
- Helper extensions in `helpers/extensions.kt`

### Test Requirements
- Tests run in a Minikube environment (for Kubernetes tests)
- Docker daemon required (for Docker tests)
- Specific images pre-pulled before tests

## Dependencies

### Key Libraries
- **Fabric8 Kubernetes Client** (7.1.0): Kubernetes API interactions
- **Docker Client** (7.0.8-OA-5): Docker API interactions (custom OpenAnalytics version)
- **Jackson** (2.18.3): JSON processing
- **Log4j** (2.24.3): Logging backend
- **Kotlin Coroutines** (1.10.1): Async programming
- **Kotlin** (2.1.10): Kotlin language and standard library
- **JUnit Jupiter** (5.11.4): Testing framework

### Repository Configuration
The project uses OpenAnalytics Nexus repositories for the custom Docker client version:
- Releases: https://nexus.openanalytics.eu/repository/releases
- Snapshots: https://nexus.openanalytics.eu/repository/snapshots

## Common Patterns

### Logging
```kotlin
private val logger = KotlinLogging.logger {}
logger.info { "Message with lazy evaluation" }
logger.warn { "Warning: ${variable}" }
```

### Error Handling
- Use `InternalException` for operator-specific errors
- Catch and log exceptions appropriately
- Use `exitProcess(1)` for fatal errors in main

### Labels
Use `LabelFactory` for consistent label creation:
- `labelsForShinyProxyInstance()` - for instances
- `labelsForShinyProxy()` - for resources

### Configuration Reading
```kotlin
val value = config.readConfigValue(default, "ENV_VAR_NAME") { it.lowercase() }
```

## Documentation

Main documentation is hosted at: https://shinyproxy.io/documentation/shinyproxy-operator/

Additional docs in `docs/` directory:
- Deployment configurations
- Prometheus integration

## CI/CD

GitHub Actions workflow in `.github/workflows/workflows.yaml`:
- Runs on Java 21
- Tests against multiple Kubernetes versions (1.30.11, 1.31.7, 1.32.3)
- Uses Minikube for integration tests
- Caches Maven dependencies

## Important Notes

- This is a critical infrastructure component for ShinyProxy deployments
- Changes should maintain backward compatibility
- Integration tests are comprehensive but can be time-consuming
- The operator manages both Kubernetes and Docker environments
- Resource cleanup is important in tests to prevent test pollution
