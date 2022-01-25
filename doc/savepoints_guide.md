# Managing savepoints with the Flink Operator

A Flink [savepoint](https://ci.apache.org/projects/flink/flink-docs-stable/ops/state/savepoints.html) is a consistent
image of the execution state of a streaming job. Users can take savepoints of a running job and restart the job from
them later. This document introduces how the Flink Operator can help you manage savepoints.

## Taking savepoints for a job

There are two ways the operator can help take savepoints for your job.

### 1. Automatic savepoints
TODO

### 2. Taking savepoints by attaching annotation to the FlinkApplication custom resource
```bash
kubectl annotate flinkapps {app_name} flinkapps.flink.k8s.io/user-control=savepoint
```