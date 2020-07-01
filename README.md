# Flink native K8s operator in Java

Flink native Kubernetes Operator is a control plane for running Apache Flink native application on Kubernetes.

## How to Build
```
   mvn clean install
```

## How to Run
* Make Sure that FlinkApplication Custom Resource Definition is already applied onto the cluster. If not, issue the following commands to apply:
 ```
 kubectl apply -f src/main/resources/crd.yaml
 ```
* Build Docker Image
```
docker build . -t flink-native-k8s-operator:1.0
docker push
```
* Start flink-native-k8s-operator deployment
A new `ServiceAccount` "flink-native-k8s-operator" will be created with enough permission to create/list pods and services.
```
 kubectl apply -f src/main/resources/flink-native-kis-operator.yaml
```
* Create a new Flink application
The flink-native-k8s-operator will watch the CRD resources and submit a new Flink application once the CR it applied.
```
kubectl apply -f src/main/resources/cr.yaml
```
* Get/List Flink applications
Get all the Flink applications running in the K8s cluster
```
kubectl get flinkapp
```

Describe a specific Flink application to show the status(including job status, savepoint, ect.)
```
kubectl describe flinkapp {app_name}
```

Trigger a new savepoint
```
kubectl edit flinkapp {app_name}
```
Edit the spec of flinkapp and increase the value of "savepointGeneration".
