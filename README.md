# Flink native K8s operator in Java

Flink native Kubernetes Operator is a control plane for running Apache Flink on Kubernetes.

## How to Build
```
   mvn clean install
```

## How to Run
- Make Sure that FlinkApplication Custom Resource Definition is already applied onto the cluster. If not, issue the following commands to apply:
 ```
 kubectl apply -f src/main/resources/crd.yaml
 ```
- Make sure that you have given correct privileges to `ServiceAccount` that would be used by the flink-native-k8s-operator `Pod`, it's `default` in our case. Otherwise you might get 403 from Kubernetes API server.
```
kubectl create clusterrolebinding flink-role-binding-default --clusterrole cluster-admin --serviceaccount=default:default

# In case of some other namespace:
kubectl create clusterrolebinding default-pod --clusterrole cluster-admin --serviceaccount=<namespace>:default
```
- Build Docker Image
```
docker build . -t flink-native-k8s-operator:1.0
docker push
```
- Start flink-native-k8s-operator deployment
```
kubectl run flink-native-k8s-operator --image flink-native-k8s-operator:1.0
```
- Create a new Flink application
```
kubectl apply -f src/main/resources/cr.yaml
```
