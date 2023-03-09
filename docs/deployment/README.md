# Deployment of ShinyProxy operator

This directory contains a set of bases that can directly be used to deploy the
ShinyProxy Operator. It also contains some example deployments in the `overlays`
directory.

## Component and dependencies

Before showing how to deploy the operator, this section describes the components
and dependencies of the operator.

- **Operator**: the operator itself which manages the different ShinyProxy
  servers.
- **ShinyProxy**: the ShinyProxy servers, these host the Shiny apps. You do not
  need to create these servers manually, since these are created by the
  operator. Instead, you define which servers to create, and the operator creates
  all necessary Kubernetes resources, without affecting any existing server or
  causing downtime.
- **Redis**: Redis is used by ShinyProxy (not by the operator) to implement
  [session and app persistence](https://shinyproxy.io/documentation/configuration/#session-and-app-persistence).
  This ensures that when a ShinyProxy server is replaced, the user is still
  logged in and all apps remain active. Redis is always required when using the
  operator. When deploying Redis on the Kubernetes cluster, we advise to
  use [Redis Sentinel](https://redis.io/docs/management/sentinel/) such that
  Redis is run in a high-available way. It is also possible to use a Redis
  server provided by cloud providers.

## Tutorial using minikube

This section provides a step-by-step tutorial on the basic deployment of the
ShinyProxy operator on minikube.

1. This tutorial requires that you install some tools:

    - [minikube](https://github.com/kubernetes/minikube)
    - [kubectl](https://github.com/kubernetes/kubectl)
    - [kustomize](https://github.com/kubernetes-sigs/kustomize)

2. Start minikube:

   ```bash
   minikube start --kubernetes-version='v1.25.6'  --addons=metrics-server,ingress --container-runtime=containerd
   ````

3. Clone this repository and change the working directory:

   ```bash
   git clone https://github.com/openanalytics/shinyproxy-operator
   cd shinyproxy-operator/docs/deployment/overlays/1-namespaced
   ```

4. Apply all resources

   ```bash
   kustomize build .  | kubectl apply -f - --server-side
   ```

   **Note:** this command may not finish successfully from the first attempt,
   for example, you could get the following message:

   ```text
   unable to recognize "STDIN": no matches for kind "CustomResourceDefinition" in version "apiextensions.k8s.io/v1"
   unable to recognize "STDIN": no matches for kind "ShinyProxy" in version "openanalytics.eu/v1"
   ```

   In this case, just re-run the command. The resources should then get created.
   (there is no way to specify the order of resources or the dependencies
   between resources in `kustomize`, re-running the command is the only
   workaround)

5. Wait for all the resources to startup. At this point the operator should
   start. It is now time to configure web access to the cluster. First get the
   IP of minikube using:

   ```bash
   minikube ip
   ```

   Next, add the following entries to `/etc/hosts`, replacing `MINIKUBE_IP` by the output of the previous command;

   ```text
   MINIKUBE_IP       shinyproxy-demo.local
   MINIKUBE_IP       shinyproxy-demo2.local
   ```

6. Once all deployments are finished, you can access ShinyProxy at
   `shinyproxy-demo.local`.
7. Wait until the ShinyProxy instance is fully started. (before you will see a
   `Not Found` page).
8. Try to launch an application and keep this application running.
9. Change something in the `resources/shinyproxy.shinyproxy.yaml` file (e.g.
   the `title` property) and then run:

   ```bash
   kubectl apply -f resources/shinyproxy.shinyproxy.yaml
   ```

   The operator now deploys a new ShinyProxy instance. The old instance will be
   kept intact as long as a Websocket connection is active on the old instance.
   The old instance will automatically be removed once it no longer has any open
   Websocket connections. New requests will immediately be handled by the new
   server as soon as it is ready. Try going to the main page of ShinyProxy and
   check whether the change your made has been applied.
10. Try the other examples:

    ```bash
    kubectl delete namespace/shinyproxy
    kubectl delete namespace/shinyproxy-operator                 # may fail
    kubectl delete namespace/shinyproxy-dept2                    # may fail
    kubectl delete namespace/my-namespace                        # may fail
    kubectl delete namespace/redis                               # may fail
    kubectl delete namespace/skipper                             # may fail
    kubectl delete -n default ingress/nginx-to-skipper-ingress  # may fail
    kubectl delete -n skipper ingress/nginx-to-skipper-ingress  # may fail
    cd directory_of_example
    kustomize build .  | kubectl apply -f -
    ```

## Overview of examples

The Operator is designed to be flexible and fit many type of deployments. This
repository includes examples for many kinds of deployments:

- *1-namespaced*:
  - Operator-mode: `namespaced`
  - Operator-namespace: `shinyproxy`
  - Redis-namespace: `shinyproxy`
  - ShinyProxy-namespace: `shinyproxy`
  - URLs: `https://shinyproxy-demo.local`

  This is a very simple deployment of the operator, where everything runs in the
  same namespace.

- *2-clustered*:
  - Operator-mode: `clustered`
  - Operator-namespace: `shinyproxy-operator`
  - Redis-namespace: `redis`
  - ShinyProxy-namespace: `shinyproxy` and `shinyproxy-dept2`
  - URLs:
    - `https://shinyproxy-demo.local`
    - `https://shinyproxy-demo2.local`

  In this example, the operator runs in `clustered` mode. Therefore, the
  operator will look into all namespaces for `ShinyProxy` resources and deploy
  these resources in their respective namespace. This example also demonstrates how
  the Operator can be used in a multi-tenancy or multi-realm way. Each
  ShinyProxy server runs in its own namespace, isolated from the other servers.
  However, they are managed by a single operator.

- *3-namespaced-hpa-app-ns*:
  - Operator-mode: `namespaced`
  - Operator-namespace: `shinyproxy`
  - Redis-namespace: `shinyproxy`
  - ShinyProxy-namespace: `shinyproxy`
  - URLs: `https://shinyproxy-demo.local`

  Similar to example 1, however, the `01_hello` app will now run in the
  `my-namespace` namespace instead of the `shinyproxy` namespace. In addition to
  the change in the `shinyproxy.shinyproxy.yaml` file, this configuration
  requires the definition of the extra namespace and the modification of
  the `ServiceAccount` of the ShinyProxy server.

- *4-namespaced-multi*:
  - Operator-mode: `namespaced`
  - Operator-namespace: `shinyproxy`
  - Redis-namespace: `shinyproxy`
  - ShinyProxy-namespace: `shinyproxy`
  - URLs:
    - `https://shinyproxy-demo.local/shinyproxy1/`
    - `https://shinyproxy-demo.local/shinyproxy2/`
    - `https://shinyproxy-demo.local/shinyproxy3/`

  Based on the second example, this example shows how multi-tenancy can be
  achieved using sub-paths instead of multiple domain names. Each ShinyProxy
  server is made available at the same domain name but at a different path under
  that domain name.

## ShinyProxy CRD

The `CustomResourceDefinition` of the operator can be found in the
`bases/namespaced/operator/crd.yaml` directory (the CRD is equal for `clustered`
and `namespaced` deployments). The following sections of this file are
important:

- `spring`: config related to Spring, such as the redis connection information
- `proxy`: the configuration of ShinyProxy, this is the same configuration as if
  you were manually deploying ShinyProxy
- `kubernetesPodTemplateSpecPatches`: allows to patch the `PodTemplate` of the
  ReplicaSet created by the operator (see
  the [example](#modify-the-shinyproxy-pod))
- `kubernetesIngressPatches`: allows to patch the `Ingress` resources created by
  the operator (see the [example](#modify-the-ingress-resource)
- `image`: the docker image to use for the ShinyProxy server (
  e.g. `openanalytics/shinyproxy:3.0.0`)
- `imagePullPolicy`: the pull policy for ShinyProxy Image; the default value is
  `IfNotPresent`; valid options are `Never`, `IfNotPresent` and `Always`.
- `fqdn`: the FQDN at which the service should be available, e.g. `
  shinyproxy-demo.local
- `appNamespaces`: a list of namespaces in which apps will be deployed. This is
  only needed when you change the namespace of an app using the
  `kubernetes-pod-patches` feature. The namespace of the operator and ShinyProxy
  instance are automatically included

## Modify the Ingress Resource

The ShinyProxy Operator automatically creates an ingress resource for each
ShinyProxy resource you create. This ingress resource points to the correct
Kubernetes service (which is also created by the operator). The created Ingress
resource contains everything that is needed for a working ShinyProxy deployment.
However, in some cases it is required to modify the resource. This can be
achieved using the `kubernetesIngressPatches` field. This field should contain a
string which contains a list of [JSON Patches](https://jsonpatch.com/) to apply
to the Ingress resource. The above examples already include the following patch:

```yaml
apiVersion: openanalytics.eu/v1
kind: ShinyProxy
metadata:
  name: shinyproxy
  namespace: shinyproxy
spec:
  proxy:
    # ...
  kubernetesIngressPatches: |
    - op: add
      path: /metadata/annotations
      value:
        nginx.ingress.kubernetes.io/proxy-buffer-size: "128k"
        nginx.ingress.kubernetes.io/ssl-redirect: "true"
        nginx.ingress.kubernetes.io/proxy-body-size: 300m
    - op: add
      path: /spec/ingressClassName
      value: nginx
  image: openanalytics/shinyproxy:3.0.0
  imagePullPolicy: Always
  fqdn: shinyproxy-demo.local
```

The first patch adds some additional annotations to the ShinyProx resource. For
example, in order to set up a redirect from HTTP to HTTPS. The second patch
changes the ingressClassName to `nginx`. Any patch is accepted, but make sure
that the resulting Ingress resource still works for the ShinyProxy Deployment.
The ShinyProxy Operator logs the manifest before and after applying the patch,
this can be useful while creating the patches.

**Note:** the previous section only applies to version 2 of the operator.
Version 1 behaves differently since it used Skipper as (intermediate) ingress
controller.

## Modify the ShinyProxy Pod

The Operator automatically creates a ReplicaSet for each ShinyProxy resource you
create. This ReplicaSet contains a `PodTemplate`, which contains all necessary
settings for creating a proper ShinyProxy pod. In a lot of cases, it can be
useful to adapt this `PodTemplate` for the specific context in which ShinyProxy
is running. For example, it's a good idea to specify the resource requests and
limits, or sometimes it's required to add a toleration to the pod. These
modification can be achieved using the `kubernetesPodTemplateSpecPatches` field.
This field should contain a string which contains a list
of [JSON Patches](https://jsonpatch.com/) to apply to the `PodTemplate`. The
above examples already include the following patch:

```yaml
apiVersion: openanalytics.eu/v1
kind: ShinyProxy
metadata:
  name: shinyproxy
  namespace: shinyproxy
spec:
  proxy:
    # ...
  kubernetesPodTemplateSpecPatches: |
    - op: add
      path: /spec/containers/0/env/-
      value:
        name: REDIS_PASSWORD
        valueFrom:
          secretKeyRef:
            name: redis
            key: redis-password
    - op: add
      path: /spec/containers/0/resources
      value:
        limits:
          cpu: 1
          memory: 1Gi
        requests:
          cpu: 0.5
          memory: 1Gi
    - op: add
      path: /spec/serviceAccountName
      value: shinyproxy-sa
  image: openanalytics/shinyproxy:3.0.0
  imagePullPolicy: Always
  fqdn: shinyproxy-demo.local
```

The above configuration contains three patches. The first patch adds an
environment variable with the password used for connecting to the Redis server.
The second patch configures the resource limits and requests of the ShinyProxy
pod. Finally, the last patch configures the `ServiceAccount` of the pod.

**Note:** it is important when using this feature to not break any existing
configuration of the pod. For example, when you want to mount additional
configmaps, use the following configuration:

```yaml
apiVersion: openanalytics.eu/v1
kind: ShinyProxy
metadata:
  name: shinyproxy
  namespace: shinyproxy
spec:
  kubernetesPodTemplateSpecPatches: |
    - op: add
      path: /spec/volumes/-
      value:
        name: myconfig
        configMap:
          name: some-configmnap
    - op: add
      path: /spec/containers/0/volumeMounts/-
      value:
        mountPath: /mnt/configmap
        name: myconfig
        readOnly: true
```

In this example, the `path` property of the patch always ends with a `-`, this
indicates that the patch adds a new entry to the end of the array
( e.g. `spec/volumes/`).

The following patch breaks the behavior of the ShinyProxy pod and should
therefore not be used:

```yaml
# NOTE: this is a demo of a WRONG configuration - do not use
apiVersion: openanalytics.eu/v1
kind: ShinyProxy
metadata:
  name: shinyproxy
  namespace: shinyproxy
spec:
  kubernetesPodTemplateSpecPatches: |
    - op: add
      path: /spec/volumes
      value:
        - name: myconfig
          configMap:
            name: some-configmnap
    - op: add
      path: /spec/containers/0/volumeMounts
      value:
        - mountPath: /mnt/configmap
          name: myconfig
          readOnly: true
```

This patch replaces the existing `/spec/volumes`
and `/spec/containers/0/volumeMounts` arrays of the pod. The ShinyProxy Operator
automatically creates a mount for a configmap which contains the ShinyProxy
configuration. By overriding these mounts, this configmap is not be mounted and
the default (demo) configuration of ShinyProxy is loaded.
