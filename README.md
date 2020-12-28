# ShinyProxy Operator

Easily run ShinyProxy on a Kubernetes cluster

#### (c) Copyright Open Analytics NV, 2020 - Apache License 2.0

## Building from source

Clone this repository and run

```
mvn -U clean install
```

The build will result in a single `.jar` file: `target/target/shinyproxy-operator-jar-with-dependencies.jar`.

## Running

The operator should be run in Kubernetes using the [docker image](https://hub.docker.com/r/openanalytics/shinyproxy-operator-snapshot).
It can run in either `clustered` scope or `namespaced` mode. In the former the operator looks for ShinyProxy instances in all namespaces while in the latter it only manages ShinyProxy instances in its own namespace.

See the [docs/deployment](docs/deployment) folder for more information.Operator 23/12/2020 or newer

## Supported Versions

| ShinyProxy Version | Operator 0.0.1-20201215.112635 or older | Operator 0.0.1-SNAPSHOT-20201223.135820 or newer |
| ------------------ | --------------------------------------- | ------------------------------------------------ |
| 2.4.2 or older     | Compatible                              | Not Compatible                                   | 
| 2.4.3 or newer     | Not Compatible                          | Compatible                                       |

## Java Version

This project requires JDK 11.