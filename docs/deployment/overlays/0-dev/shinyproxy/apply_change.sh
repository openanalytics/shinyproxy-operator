#!/usr/bin/env bash

sed -i "s/ShinyProxy 1/ShinyProxy 1 (modified)/g" shinyproxy1.yaml
sed -i "s/ShinyProxy 2/ShinyProxy 2 (modified)/g" shinyproxy2.yaml
sed -i "s/ShinyProxy 3/ShinyProxy 3 (modified)/g" shinyproxy3.yaml
sed -i "s/ShinyProxy 4/ShinyProxy 4 (modified)/g" shinyproxy4.yaml

kubectl apply -f shinyproxy1.yaml
kubectl apply -f shinyproxy2.yaml
kubectl apply -f shinyproxy3.yaml
kubectl apply -f shinyproxy4.yaml
