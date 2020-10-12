## Deployment of ShinyProxy operator

This documentation provides an example deployment of the ShinyProxy operator on minikube.

 1. Start minikube `minikube start`
 2. Create the CRD: `kubectl apply -f crd.yaml`
 3. Goto either the `namespaced` or `clustered` directory
 4. Create the namespaces
    ```
    kubectl apply -f namespaces.yaml
    ```
 5. Setup Redis and Skipper
    ```
    kubectl apply -f dependencies/
    ```
 6. Create the example secrets

    ```
    kubectl apply -f secrets.yaml
    ```
 7. Setup RBAC config
    ```
    kubectl apply -f operator.serviceAccount.yaml
    kubectl apply -f shinyproxy.serviceAccount.yaml
    ```
 8. Setup deployment
    ```
    kubectl apply -f deployment.yaml
    ```
    At this point the operator should start.
    The Skipper ingress created a service. You can find the URL of this service using `minikube service --url skipper-ingress -n kube-system`.
    See below for a temporary ingress setup.
    Note that you should always access the Ingress using HTTPS, otherwise the cookie-based routing will not work.
 9. Deploy ShinyProxy: the `shinyproxy.yaml` file contains an example configuration for the operator.
    Some important parts of it:

     - `spring` config related to Spring, such as the redis connection information
     - `proxy` the configuration of ShinyProxy, this is the same configuration as if you were manually deploying ShinyProxy
     - `kubernetesPodTemplateSpecPatches` allows to patch the PodTemplate
     - `image` the docker image to use for the ShinyProxy instances
     - `fqdn` the FQDN at which the service should be available
     - `appNamespaces` a list of namespaces in which apps will be deployed. This is only needed when you change the namespace of an app using the `kubernetes-pod-patches` feature. The namespace of the operator and ShinyProxy instance are automatically included.
    ```
    kubectly apply -f shinyproxy.yaml
    ```
 10. Now open the app (using https://shinyproxy-demo.local) and start some application.
 11. Change something in the `shinyproxy.yaml` file and then run:
    ```
    kubectl apply -f deployment.yaml
    ```
    The operator now deploys a new ShinyProxy instance. As long as the old instance is being used (i.e. apps are running on it), the old instance will be kept intact.
    Users using the old instance, will stay on the old instance.
    The old instance will automatically be removed once no apps are running on it.

## Setup ingress (only on dev machines)

Download the [ssl-proxy](https://github.com/suyashkumar/ssl-proxy) tool.
Next start it up with the correct url of the Skipper service:

```
sudo ./ssl-proxy-linux-amd64 -from 127.0.0.1:443 -to `minikube service --url skipper-ingress -n kube-system`
```

Add a line to `/etc/hosts`:

```
127.0.0.1       shinyproxy-demo.local
```

Now you can acess the service at `shinyproxy-demo.local`.
In a production environment, you can use a loadbalancer from a cloud provider or use e.g. nginx in front of Skipper.


## Deploying ShinyProxy with multiple realms

This section describes the deployment of ShinyProxy in the case that multiple realms are required.
Most deployments don't need this part.

Sometimes deployments need to support multiple authentication options. For example combining LDAP with OIDC or supporting two OIDC realms.
To achieve this, multiple instances of ShinyProxy can be deployed at different sub-paths.
The configuration in the [multi-realm](multi-realm) directory configures two ShinyProxy instances:
 - **realm1**: using LDAP under sub-path `/realm1`
 - **realm2**: using simple authentication under sub-path `/realm2`

When deployed, the operator creates two ShinyProxy instances, which work completely independent.
Both configurations can be changed without affecting the other. Therefore this setup can also be used for multi-tenant setups.

The [multi-realm/realm-selector](multi-realm/realm-selector) directory contains a very simple docker container to host a webpage to allow users to select their corresponding realm.
To use this app:
 - build the docker image
 - push it some registry
 - change the image tag in deployment.yaml
 - `kubectl apply -f deployment.yaml service.yaml skipper-ingress.yaml`
