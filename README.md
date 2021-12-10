# ShinyProxy Operator

Easily run ShinyProxy on a Kubernetes cluster

**(c) Copyright Open Analytics NV, 2020-2021 - Apache License 2.0**

## Why?

Deploying and managing ShinyProxy can get complex when many apps are used,
especially when the configuration of ShinyProxy is often updated. When
restarting a running ShinyProxy instance (in order to update its configuration),
users will face a disconnect from their running applications. The only solution
to guarantee that users do not lose their connection to running apps, is to keep
the current instance alive when updating ShinyProxy's configuration. However,
manually keeping track of these instances would be too cumbersome and should
therefore be automated.

The ShinyProxy operator for Kubernetes is able to manage multiple ShinyProxy
instances and their configuration. To give an example of the working of the
operator, assume we have some ShinyProxy configuration `config1` which contains
one app called `app1`. When the operator starts working, it checks whether a
ShinyProxy instance exists with that configuration. If not, it starts a
ShinyProxy instance and all other required configuration. Users can now start
using `app1` on this instance. Some time later, the need for a second app
arises. Therefore, the administrator adapts the configuration of ShinyProxy to
include a second app `app2`. However, some users are still using `app1` on the
old instance. These apps may have some state, which should not be lost.
Therefore, the operator starts a second ShinyProxy instance using configuration
`config2`. The operator ensures that users which are currently using the first
instance, stay on that instance. All other users, are forwarded to the new
server and can use the new application. Nevertheless, users using an old
instance can choose to use the new instance, by clicking a button in the user
interface. The operator stops the old instance once it has no apps running.

## Building from source

Clone this repository and run

```bash
mvn -U clean install
```

The build will result in a single `.jar` file:
`target/target/shinyproxy-operator-jar-with-dependencies.jar`.

## Running

The operator should be run in Kubernetes using the [docker image](https://hub.docker.com/r/openanalytics/shinyproxy-operator).
It can run in either `clustered` scope or `namespaced` mode. In the former the
operator looks for ShinyProxy instances in all namespaces while in the latter it
only manages ShinyProxy instances in its own namespace.

See the [docs/deployment](docs/deployment) folder for more information.

## Configuration

We try to keep the configuration of the Operator itself as minimum as possible.
Furthermore, we want the operator to work without configuration in most cases.
Nevertheless, for some specific cases some configuration options are available.
For now these options are specified using environment variables. All variables
start with the `SPO` prefix, meaning **S**hiny**P**roxy**O**perator.

- `SPO_MODE`: can either be `namespaced` or `clustered` (default). This
  specifies whether the operator should only look in its own namespace for
  ShinyProxy configurations or in all namespaces.
- `SPO_DISABLE_SECURE_COOKIES`: when set to any value, this disables the
  `secure` flag on all cookies used by the Operator.
- `SPO_PROBE_INITIAL_DELAY`: specifies the initial delay of the Readiness and
  Liveness probes. This is useful when the used Kubernetes version does not
  support startup probes.
- `SPO_PROBE_FAILURE_THRESHOLD`: specifies the failure threshold of the
  Readiness and Liveness probes. This is useful when the used Kubernetes version
  does not support startup probes.
- `SPO_PROBE_TIMEOUT`: specifies the timeout in seconds of the Readiness and
  Liveness probes. This is useful when the used Kubernetes version does not
  support startup probes.
- `SPO_STARTUP_PROBE_INITIAL_DELAY`: specifies the initial delay of the StartUp probe. By default, this is 60 seconds.
- `SPO_LOG_LEVEL`: configures the log level of the operator, may be one of the
  following:
  - `OFF`: disables logging
  - `ERROR`
  - `WARN`
  - `INFO`
  - `DEBUG`: default (may change)
  - `TRACE`
  - `ALL`: enables all logging

Note: in our deployments where startup probes aren't supported we have success
with the following configuration:

- `SPO_PROBE_INITIAL_DELAY` to something between 40 and 140 depending on the
  performance of the cluster.
- `SPO_PROBE_FAILURE_THRESHOLD` to `2`
- `SPO_PROBE_TIMEOUT` to `3`

## Supported Versions

The first stable release of the operator (1.0.0) is compatible with ShinyProxy
2.6.0. Older ShinyProxy versions are supported by running development snapshots
of the operator, however, we strongly advice to upgrade to the latest version of
ShinyProxy and the operator for the best experience.

| ShinyProxy Version | Minimum operator version         | Maximum operator version (inclusive) |
|--------------------|----------------------------------|--------------------------------------|
| 2.4.3              | `0.0.1-SNAPSHOT-20201215.112635` | `0.0.1-SNAPSHOT-20201215.112635`     |
| 2.5.0              | `0.0.1-SNAPSHOT-20210302.095930` | `0.0.1-SNAPSHOT-20210607.070151`     |
| 2.6.0              | 1.0.0                            | TBD                                  |

## Kubernetes versions

|                | k8s 1.22.x | k8s >= v1.21.3 | k8s <= v1.21.2 | k8s >= 1.20.10 | k8s <= v1.20.9 | v1.19 | v1.18 | v1.17 | v1.16 | v1.15 | v1.14 |
|----------------|------------|----------------|----------------|----------------|----------------|-------|-------|-------|-------| ----- | ----- |
| 1.1.0³          | ✓          | ✓              | ✓²              | ✓              | ✓²              | ✓     | -     | -     | -     | -    | - |
| 1.0.0          | -          | ✓              | ✓²              | ✓              | ✓²              | ✓     | ✓     | ✓¹     | ✓¹     | -⁴  | -⁴ |
| 0.0.1-SNAPSHOT | -          | ✓              | ✓²              | ✓              | ✓²              | ✓     | ✓     | ✓¹     | ✓¹     | ✓¹  | ✓¹ |

**Note:**

- ¹ requires the use of `SPO_PROBE_INITIAL_DELAY` and `SPO_PROBE_FAILURE_THRESHOL` due to lack of startup probes
- ² requires a workaround, see below.
- ³ not yet released; this version will use the `extensions/v1beta1` version of `Ingress` which is removed in k8s v1.22, but only available from v1.19
- ⁴ version 1.0.0 uses version `apiextensions.k8s.io/v1` of the `CustomResourceDefinition` resource, which does not exists in versions before v1.16

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

### Update to 1.0.0

Be aware of these changes when updating to version 1.0.0:

- the ShinyProxy CRD now uses version `apiextensions.k8s.io/v1` of the
  `CustomResourceDefinition` resource instead of version
  `apiextensions.k8s.io/v1beta`. In our tests this update when smooth, but
  please take into account that you may be required to re-create the CRD and
  that therefore your ShinyProxy servers may have to be re-created (causing
  downtime).
- because of this change, the operator requires at least version Kubernetes
  v1.16.
- the ShinyProxy CRD now specifies version `openanalytics.eu/v1` instead of
  `openanalytics.eu/v1alpha1`. Nevertheless, the operator is still able to
  handle ShinyProxy resources using the `openanalytics.eu/v1alpha1` version.
  When creating resources with version `openanalytics.eu/v1alpha1`, Kubernetes
  will automatically convert these to use version `openanalytics.eu/v1`.


## Java Version

This project requires JDK 11.
