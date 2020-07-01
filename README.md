# Flink native K8s operator in Java

Flink native Kubernetes Operator is a control plane for running Apache Flink native application on Kubernetes. It is java 
implemented operator, which use fabric8 [Kubernetes Client](https://github.com/fabric8io/kubernetes-client) to contact with K8s apiserver.
Also instead of running shell commands `flink run/run-application` to start a Flink application, we choose to use Flink 
client interfaces to do this(e.g. `ApplicationDeployer`). Same for the savepoint and job status. Benefit from this, the operator will
have better performance to launch multiple applications.

## How to Build
```
   mvn clean install
```

## How to Run
* Make Sure that FlinkApplication Custom Resource Definition is already applied onto the cluster. The CRD could be find [here](deploy/crd.yaml). If not, issue the following commands to apply:
 ```
 kubectl apply -f deploy/crd.yaml
 ```
* Build Docker Image
```
docker build . -t flink-native-k8s-operator:1.0
docker push
```
* Start flink-native-k8s-operator deployment
A new `ServiceAccount` "flink-native-k8s-operator" will be created with enough permission to create/list pods and services.
```
 kubectl apply -f deploy/flink-native-k8s-operator.yaml
```
* Create a new Flink application
The flink-native-k8s-operator will watch the CRD resources and submit a new Flink application once the CR it applied.
```
kubectl apply -f deploy/cr.yaml
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
# Edit the spec of flinkapp and increase the value of "savepointGeneration".
```
