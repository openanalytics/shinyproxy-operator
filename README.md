# ShinyProxy Operator

Easily run ShinyProxy on a Kubernetes cluster

**(c) Copyright Open Analytics NV, 2020-2021 - Apache License 2.0**

## Why?

Deploying and managing ShinyProxy can get complex when many apps are used, especially when the configuration of
ShinyProxy is often updated. When restarting a running ShinyProxy instance (in order to update its configuration), users
will face a disconnect from their running applications. The only solution to guarantee that users do not lose their
connection to running apps, is to keep the current instance alive when updating ShinyProxy's configuration. However,
manually keeping track of these instances would be too cumbersome and should therefore be automated.

The ShinyProxy operator for Kubernetes is able to manage multiple ShinyProxy instances and their configuration. To give
an example of the working of the operator, assume we have some ShinyProxy configuration `config1` which contains one app
called `app1`. When the operator starts working, it checks whether a ShinyProxy instance exists with that configuration.
If not, it starts a ShinyProxy instance and all other required configuration. Users can now start using `app1` on this
instance. Some time later, the need for a second app arises. Therefore the administrator adapts the configuration of
ShinyProxy to include a second app `app2`. However, some users are still using `app1` on the old instance. These apps
may have some state, which should not be lost. Therefore, the operator starts a second ShinyProxy instance using
configuration
`config2`. The operator ensures that users which are currently using the first instance, stay on that instance. All
other users, are forwarded to the new server and can use the new application. Nevertheless, users using an old instance
can choose to use the new instance, by clicking a button in the user interface. The operator stops the old instance once
it has no apps running.

## Building from source

Clone this repository and run

```bash
mvn -U clean install
```

The build will result in a single `.jar` file: `target/target/shinyproxy-operator-jar-with-dependencies.jar`.

## Running

The operator should be run in Kubernetes using
the [docker image](https://hub.docker.com/r/openanalytics/shinyproxy-operator-snapshot). It can run in
either `clustered` scope or `namespaced` mode. In the former the operator looks for ShinyProxy instances in all
namespaces while in the latter it only manages ShinyProxy instances in its own namespace.

See the [docs/deployment](docs/deployment) folder for more information.

## Configuration

We try to keep the configuration of the Operator itself as minimum as possible. Furthermore we want the operator to work
without configuration in most cases. Nevertheless, for some specific cases some configuration options are available. For
now these options are specified using environment variables. All variables start with the `SPO` prefix, meaning **S**
hiny**P**roxy**O**perator.

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

The first stable release of the operator (0.1.0) is compatible with ShinyProxy 2.6.0. Older ShinyProxy versions are
supported by running development snapshots of the operator, however, we strongly advice to upgrade to the latest version
of ShinyProxy and the operator for the best experience.

| ShinyProxy Version | Minimum operator version         | Maximum operator version (inclusive) |
|--------------------|----------------------------------|--------------------------------------|
| 2.4.3              | `0.0.1-SNAPSHOT-20201215.112635` | `0.0.1-SNAPSHOT-20201215.112635`     |
| 2.5.0              | `0.0.1-SNAPSHOT-20210302.095930` | `0.0.1-SNAPSHOT-20210607.070151`     |
| 2.6.0              | 0.1.0                            | N/A                                  |

## Kubernetes versions

| Kubernetes Version | Minimal required operator version | Notes                                                                                                                                                                                                                       |
|--------------------|-----------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| v1.16              | Any version                       | Requires the use of `SPO_PROBE_INITIAL_DELAY` and `SPO_PROBE_FAILURE_THRESHOL` due to lack of startup probes.                                                                                                               |
| v1.17              | Any version                       | Requires the use of `SPO_PROBE_INITIAL_DELAY` and `SPO_PROBE_FAILURE_THRESHOL` due to lack of startup probes.                                                                                                               |
| v1.18              | Any version                       | Includes startup probes (as beta).                                                                                                                                                                                          |
| v1.19              | Any version                       |                                                                                                                                                                                                                             |
| v1.20.9 or older   | 0.1.0-SNAPSHOT-20210831.075527    | Requires workaround - see below.                                                                                                                                                                                            |
| v1.20.10 or newer  | 0.0.1-SNAPSHOT-20210113.083121    | This version is not officially supported by the Kubernetes API Client we use, however, integration and manual tests are performed against this version. Version 0.2.0 of the operator will officially support this version. |
| v1.21.2 or older   | 0.1.0-SNAPSHOT-20210831.075527    | Requires workaround - see below.                                                                                                                                                                                            |
| v1.21.3 or newer   | 0.0.1-SNAPSHOT-20210113.083121    | This version is not officially supported by the Kubernetes API Client we use, however, integration and manual tests are performed against this version. Version 0.2.0 of the operator will officially support this version. |
| v1.22              | N/A                               | This version has not yet been tested with the operator. Version 0.2.0 of the operator will officially support this version.                                                                                                 |

### Workaround for bug in Kubernetes

A [bug](https://github.com/kubernetes/kubernetes/issues/102464) affecting the
operator was introduced in Kubernetes 1.20 and fixed in versions 1.20.10 and
1.21.3. However, some deployments (e.g. using EKS) are not able to use this
version. When using the affected versions, Kubernetes stops sending events for
the `Service` resources after a certain amount of time. Therefore, the Operator
is unaware of any events happening on services and is therefore unable to fully
configure a ShinyProxy server. The bug only occurs after the operator has been
running for a random time between 30 minutes and two hours. Unfortunately, the
only reasonable work-around is to regularly restart the Operator. Since version
`0.1.0-SNAPSHOT-20210831.075527`, it is possible to specify the
`SPO_PROCESS_MAX_LIFETIME` environment variable. After the configured time (in
minutes), the operator stops. The corresponding Docker container then
automatically restarts the Java process.

## Java Version

This project requires JDK 11.
