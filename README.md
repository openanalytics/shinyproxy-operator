# ShinyProxy Operator

Easily run ShinyProxy on a Kubernetes cluster

#### (c) Copyright Open Analytics NV, 2020-2021 - Apache License 2.0

## Building from source

Clone this repository and run

```
mvn -U clean install
```

The build will result in a single `.jar` file: `target/target/shinyproxy-operator-jar-with-dependencies.jar`.

## Running

The operator should be run in Kubernetes using
the [docker image](https://hub.docker.com/r/openanalytics/shinyproxy-operator-snapshot). It can run in
either `clustered` scope or `namespaced` mode. In the former the operator looks for ShinyProxy instances in all
namespaces while in the latter it only manages ShinyProxy instances in its own namespace.

See the [docs/deployment](docs/deployment) folder for more information.Operator 23/12/2020 or newer

## Configuration

We try to keep the configuration of the Operator itself as minimum as possible. Furthermore we want the operator to work
without configuration in most cases. Nevertheless, for some specific cases some configuration options are available. For
now these options are specified using environment variables. All variables start with the `SPO` prefix, meaning **S**hiny**P**roxy**O**perator.

- `SPO_MODE`: can either be `namespaced` or `clustered` (default). This specifies whether the operator should only look
  in its own namespace for ShinyProxy configurations or in all namespaces.
- `SPO_DISABLE_SECURE_COOKIES`: when set to any value, this disables the `secure` flag on all cookies used by the
  Operator.
- `SPO_PROBE_INITIAL_DELAY`: specifies the initial delay of the Readiness and Liveness probes. This is useful when the
  used Kubernetes version does not support startup probes.
- `SPO_PROBE_FAILURE_THRESHOLD`: specifies the failure threshold of the Readiness and Liveness probes. This is useful
  when the used Kubernetes version does not support startup probes.
- `SPO_PROBE_TIMEOUT`: specifies the timeout in seconds of the Readiness and Liveness probes. This is useful when the
  used Kubernetes version does not support startup probes.
- `SPO_STARTUP_PROBE_INITIAL_DELAY`: specifies the initial delay of the StartUp probe. By default this is 60 seconds.
- `SPO_LOG_LEVEL`: configures the log level of the operator, may be one of the following:
    - `OFF`: disables logging
    - `ERROR`
    - `WARN`
    - `INFO`
    - `DEBUG`: default (may change)
    - `TRACE`
    - `ALL`: enables all logging

Note: in our deployments where startup probes aren't supported we have success with the following configuration:

- `SPO_PROBE_INITIAL_DELAY` to something between 40 and 140 depending on the performance of the cluster.
- `SPO_PROBE_FAILURE_THRESHOLD` to `2`
- `SPO_PROBE_TIMEOUT` to `3`

## Supported Versions

| ShinyProxy Version  | Operator 0.0.1-20201215.112635 or older | Operator 0.0.1-SNAPSHOT-20210302.095930 or newer        |
| ------------------- | --------------------------------------- | ------------------------------------------------------- |
| 2.4.3 or older      | Compatible                              | Not Compatible                                          |
| 2.5.0 or newer      | Not Compatible                          | Compatible                                              |

## Kubernetes versions

| Kubernetes Version | Minimal required operator version      | Notes                                                                                                          |
| ------------------ | -------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| v1.16              | Any version                            | Requires the use of `SPO_PROBE_INITIAL_DELAY` and `SPO_PROBE_FAILURE_THRESHOL` due to lack of startup probes.  |
| v1.17              | Any version                            | Requires the use of `SPO_PROBE_INITIAL_DELAY` and `SPO_PROBE_FAILURE_THRESHOL` due to lack of startup probes.  |
| v1.18              | Any version                            | Includes startup probes (as beta).                                                                             |
| v1.19              | Any version                            |                                                                                                                |
| v1.20              | 0.0.1-SNAPSHOT-20210113.083121         |                                                                                                                |

## Java Version

This project requires JDK 11.
