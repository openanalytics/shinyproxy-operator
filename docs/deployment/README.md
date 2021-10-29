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
  session persistence. This ensures that when a ShinyProxy server is replaced,
  the user is still logged in. This provides a smooth transition when the
  operator replaces a ShinyProxy server.
- **Skipper**: Skipper is used as an ingress controller. Currently, this is the
  only ingress controller supported by the operator. The reason is that the
  ingress controller is responsible for routing users to the correct ShinyProxy
  server. Skipper is the only ingress controller we found suitable for this
  feature. However, this does not mean you will have to replace your existing
  ingress solution. You can deploy Skipper as an "intermediate" ingress
  controller and forward your existing ingress controller to the Skipper
  service. In fact, all example in this repository assume this way of working.

## Tutorial using minikube

This section provides a step-by-step tutorial on the basic deployment of the
ShinyProxy operator on minikube.

1. This tutorial requires that you install some tools:

    - [minikube](https://github.com/kubernetes/minikube)
    - [kubectl](https://github.com/kubernetes/kubectl)
    - [kustomize](https://github.com/kubernetes-sigs/kustomize)

2. Start minikube:

   ```bash
   minikube start --addons=metrics-server,ingress
   ````

3. Clone this repository and change the working directory:

   ```bash
   git clone https://github.com/openanalytics/shinyproxy-operator
   cd shinyproxy-operator/docs/deployment/overlays/1-namespaced-hpa
   ```

4. Apply all resources

   ```bash
   kustomize build .  | k apply -f -
   ```

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
9. Change something in the `shinyproxy/shinyproxy.yaml` file and then run:

   ```bash
   kubectl apply -f shinyproxy.yaml
   ```

   The operator now deploys a new ShinyProxy instance. As long as the old
   instance is being used (i.e. apps are running on it), the old instance will
   be kept intact. Users using the old instance, will stay on the old instance.
   The old instance will automatically be removed once no apps are running on
   it.
10. Try the other examples:

    ```bash
    kubectl delete namespace/shinyproxy
    kubectl delete namespace/shinyproxy-operator                 # may fail
    kubectl delete namespace/shinyproxy-dept2                    # may fail
    kubectl delete namespace/my-namespace                        # may fail
    kubectl delete namespace/redis                               # may fail
    kubectl delete namespace/skipper                             # may fail
    kubectl delete -n default ingress/ngingx-to-skipper-ingress  # may fail
    kubectl delete -n skipper ingress/ngingx-to-skipper-ingress  # may fail
    cd directory_of_example
    kustomize build .  | k apply -f -
    ```

## Overview of examples

The Operator is designed to be flexible and fit many type of deployments. This repository includes examples for many kinds of deployments:

- *1-namespaced-hpa*:
  - Operator-mode: `namespaced`
  - Operator-namespace: `shinyproxy`
  - Skipper-namespace: `shinyproxy`
  - Redis-namespace: `shinyproxy`
  - Skipper deployment: `Deployment` + `HorizontalPodAutoScaler`
  - ShinyProxy-namespace: `shinyproxy`
  - URLs: `https://shinyproxy-demo.local`

  This is a very simple deployment of the operator, where everything runs in the same namespace.

- *2-namespaced-ds*:
  - Operator-mode: `namespaced`
  - Operator-namespace: `shinyproxy`
  - Skipper-namespace: `shinyproxy`
  - Redis-namespace: `shinyproxy`
  - Skipper deployment: `DaemonSet`
  - ShinyProxy-namespace: `shinyproxy`
  - URLs: `https://shinyproxy-demo.local`

  This deployment is very similar to the previous one, except that it runs Skipper using a `DaemonSet` instead of an automatically scaling `Deployment`. Using the `DaemonSet` ensures that every Kubernetes nodes contains a Skipper pod. This is useful when you want a predictable amount of Skipper pods. In the previous example, the cluster automatically scales the amount of Skipper pods according to the load of these pods. When properly configured, this ensures that Skipper has enough resources to
  do its work, while not wasting resources.

- *3-clustered-hpa*:
  - Operator-mode: `clustered`
  - Operator-namespace: `shinyproxy-operator`
  - Skipper-namespace: `skipper`
  - Redis-namespace: `redis`
  - Skipper deployment: `Deployment` + `HorizontalPodAutoScaler`
  - ShinyProxy-namespace: `shinyproxy` and `shinyproxy-dept2`
  - URLs:
    - `https://shinyproxy-demo.local`
    - `https://shinyproxy-demo2.local`

  In this example, the operator runs in `clustered` mode. Therefore, the operator will look into all namespaces for `ShinyProxy` resources and deploy these resources in their respective namespace. This also requires Skipper to be run in clustered mode (in the previous examples it would only look at `Ingress`
  definitions in the `shinyproxy` namespace.) This example also demonstrates how the Operator can be used in a multi-tenancy or multi-realm way. Each ShinyProxy server runs in its own namespace, isolated from the other servers. However, they are managed by a single operator.

- *4-clustered-ds*:
  - Operator-mode: `clustered`
  - Operator-namespace: `shinyproxy-operator`
  - Skipper-namespace: `skipper`
  - Redis-namespace: `redis`
  - Skipper deployment: `DaemonSet`
  - ShinyProxy-namespace: `shinyproxy` and `shinyproxy-dept2`
  - URLs:
    - `https://shinyproxy-demo.local`
    - `https://shinyproxy-demo2.local`

  Equal to example 3, except that Skipper is deployed using a `DaemonSet` instead of `Deployment`.

- *5-namespaced-hpa-app-ns*:
  - Operator-mode: `namespaced`
  - Operator-namespace: `shinyproxy`
  - Skipper-namespace: `shinyproxy`
  - Redis-namespace: `shinyproxy`
  - Skipper deployment: `Deployment` + `HorizontalPodAutoScaler`
  - ShinyProxy-namespace: `shinyproxy`
  - URLs: `https://shinyproxy-demo.local`

  Similar to example 1, however, the `01_hello` app will now run in the
  `my-namespace` namespace instead of the `shinyproxy` namespace. In addition to the change in the `shinyproxy.yaml`, this configuration requires the definition of the extra namespace and the modification of the `ServiceAccount`
  of the ShinyProxy server.

- *6-namespaced-ds-multi*:
  - Operator-mode: `namespaced`
  - Operator-namespace: `shinyproxy`
  - Skipper-namespace: `shinyproxy`
  - Redis-namespace: `shinyproxy`
  - Skipper deployment: `DaemonSet`
  - ShinyProxy-namespace: `shinyproxy`
  - URLs:
    - `https://shinyproxy-demo.local/shinyproxy1/`
    - `https://shinyproxy-demo.local/shinyproxy2/`
    - `https://shinyproxy-demo.local/shinyproxy3/`

  Based on example 2, this example shows how multi-tenancy can be achieved using
  sub-paths instead of multiple domain names. Each ShinyProxy server is made
  available at the same domain name but at a different path under that
  domain name.

## ShinyProxy CRD

The `CustomResourceDefinition` of the operator can be found in the
`bases/namespaced/operator/crd.yaml` directory (the CRD is equal for `clustered`
and `namespaced` deployments). The following sections of this file are
important:

- `spring` config related to Spring, such as the redis connection information
- `proxy` the configuration of ShinyProxy, this is the same configuration as if
  you were manually deploying ShinyProxy
- `kubernetesPodTemplateSpecPatches` allows to patch the PodTemplate
- `image` the docker image to use for the ShinyProxy instances
- `fqdn` the FQDN at which the service should be available
- `appNamespaces` a list of namespaces in which apps will be deployed. This is
  only needed when you change the namespace of an app using the
  `kubernetes-pod-patches` feature. The namespace of the operator and ShinyProxy
  instance are automatically included
