# ShinyProxy Operator

Easily run ShinyProxy on a Kubernetes cluster

**(c) Copyright Open Analytics NV, 2020-2024 - Apache License 2.0**

## Why?

Deploying and managing ShinyProxy can get complex when many apps are used,
especially when the configuration of ShinyProxy is often updated.

When restarting a running ShinyProxy instance (in order to update its
configuration), users will face a disconnect from their running applications.
The only solution to guarantee that users do not lose their connection to
running apps, is to keep the current instance alive when updating ShinyProxy's
configuration. However, manually keeping track of these instances would be too
cumbersome and should therefore be automated.

The ShinyProxy operator for Kubernetes is able to manage multiple ShinyProxy
instances and their configuration.

### Example

Assume we have some ShinyProxy configuration `config1` which contains one app
called `app1`. When the operator starts working, it checks whether a ShinyProxy
instance exists with that configuration. If not, it starts a ShinyProxy instance
and all other required configuration. Users can now start using `app1` on this
instance.

Some time later, the need for a second app arises. Therefore, the administrator
adapts the configuration of ShinyProxy to include a second app `app2`.

However, some users are still using `app1` on the old instance. These apps may
have some state, which should not be lost. Therefore, the operator starts a
second ShinyProxy instance using configuration `config2`. The operator does not
modify the original ShinyProxy server, therefore the existing apps continue to
work (even if they are using Websocket connections).

All new HTTP (and Websocket) connections are forwarded to the new server, i.e.
any new connection is handled by the new server. Therefore, if users go to the
main ShinyProxy page, they will see that a new app is available. Every user (
also those still using the old application) can start the new app. The operator
stops and removes the old server as soon as it has finished handling any
existing (Websocket) connections.

## Building from source

Clone this repository and run

```bash
mvn -U clean install
```

The build will result in a single `.jar` file:
`target/target/shinyproxy-operator-jar-with-dependencies.jar`.

## Running

The operator should be run in Kubernetes using
the [docker image](https://hub.docker.com/r/openanalytics/shinyproxy-operator).
It can run in either `clustered` or `namespaced` mode. In the former the
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
- `SPO_PROBE_INITIAL_DELAY`: specifies the initial delay of the Readiness and
  Liveness probes. This is useful when the used Kubernetes version does not
  support startup probes.
- `SPO_PROBE_FAILURE_THRESHOLD`: specifies the failure threshold of the
  Readiness and Liveness probes. This is useful when the used Kubernetes version
  does not support startup probes.
- `SPO_PROBE_TIMEOUT`: specifies the timeout in seconds of the Readiness and
  Liveness probes. This is useful when the used Kubernetes version does not
  support startup probes.
- `SPO_STARTUP_PROBE_INITIAL_DELAY`: specifies the initial delay of the StartUp
  probe. By default, this is 60 seconds.
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
| 3.1.0              | 2.1.0                            | TBD                                  |
| 3.0.0              | 2.0.0                            | TBD (works with 2.1.0)               |
| 2.6.0              | 1.0.0                            | 1.1.0                                |
| 2.5.0              | `0.0.1-SNAPSHOT-20210302.095930` | `0.0.1-SNAPSHOT-20210607.070151`     |
| 2.4.3              | `0.0.1-SNAPSHOT-20201215.112635` | `0.0.1-SNAPSHOT-20201215.112635`     |

## Kubernetes versions

|       | k8s 1.29.x | k8s 1.28.x | k8s 1.27.x | k8s 1.26.x | k8s 1.25.x | k8s 1.24.x | k8s 1.23.x | k8s 1.22.x | k8s >= v1.21.3 | k8s <= v1.21.2 | k8s >= 1.20.10 | k8s <= v1.20.9 | v1.19 | <= v1.18 |
|-------|------------|------------|------------|------------|------------|------------|------------|------------|----------------|----------------|----------------|----------------|-------|----------|
| 2.1.0 | ✓          | ✓          | ✓          | ✓          | ✓          | ✓          | ✓          | ✓          | ✓              | ✓¹             | ✓              | ✓¹             | 	✓    | 	-       |
| 2.0.0 | ✓²         | ✓²         | ✓²         | ✓²         | ✓²         | ✓²         | ✓          | ✓          | ✓              | ✓¹             | ✓              | ✓¹             | 	✓    | 	-       |

**Note:**

- ¹ requires a workaround, see below.
- ² version 2.0.0 supports these Kubernetes versions, but might stop syncing after some time, this issue is solved in version 2.1.0

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

### Update to 2.0.0

Be aware of these changes when updating to version 2.0.0:

- the old mechanism where cookies were used to assign users to specific
  ShinyProxy servers is no longer used. Instead, as soon as a new server is
  started, all new requests will be handled by the new server, including
  requests for existing apps. Only existing websocket connections will stay open
  on the old servers. This has multiple benefits:
    - when a new server is started, users will immediately use and see the
      configuration of that new server. In other words, if a new configuration
      includes a new app, this app is immediately available to all users (even
      if they are using apps started on older servers)
    - there is no longer a process of transferring users to new servers. Both
      the forced method and the manual method (where users have to click a
      button) are removed. Users will immediately use the new configuration.
    - apps can be run for a (very) long time, even if frequently updating the
      configuration and without having many old servers. Old servers are removed
      as soon as no websocket connections are running on that server.
    - Skipper is no longer a dependency of the operator. There is no benefit in
      using with version two of the operator.
- the operator now requires ShinyProxy to store the active proxies in Redis.
  Therefore, since this release Redis takes a more critical role. When running
  Redis inside Kubernetes, it is therefore best practice to use Redis Sentinel.
  This way Redis runs in a High Available mode, using three replicas. Compared
  to running a single Redis server, this prevents a single point of failure on
  Redis and the node it is running on. This repository contains all manifests
  required to set up Redis Sentinel (based on the bitnamai Redis helm chart).

The best way to update to ShinyProxy 2.0.0 is by creating a fresh deployment of
the operator and migrating users to this new deployment. The following changes
need to be made to the ShinyProxy configuration file:

- add the property `proxy.store-mode: Redis`
- add the property `proxy.stop-proxies-on-shutdown: false`
- optionally add the
  property [`kubernetesIngressPatches`](docs/deployment#modify-the-ingress-resource)
  in order to customize the ingress created by the operator.
- update the ShinyProxy image to `openanalytics/shinyproxy:3.1.1`

### Update to 2.1.0

The [ShinyProxy CRD](docs/deployment/bases/namespaced/crds/shinyproxy.crd.yaml)
has been updated in version 2.1.0, it is important to update the CRD in your
cluster. Running the [deployment commands](docs/deployment/) is enough. The CRD
can be updated while ShinyProxy and the ShinyProxy operator are running in the
cluster.

## Java Version

This project requires JDK 17.
