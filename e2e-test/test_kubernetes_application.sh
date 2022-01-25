#!/usr/bin/env bash
################################################################################
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
################################################################################

CLUSTER_ID="flink-example-statemachine"
TIMEOUT=300
TEST_NAMESPACE="flink-test"

function wait_for_logs {
  local jm_pod_name=$1
  local successful_response_regex=$2

  # wait or timeout until the log shows up
  echo "Waiting for log \"$2\"..."
  for i in $(seq 1 ${TIMEOUT}); do
    if kubectl -n ${TEST_NAMESPACE} logs $jm_pod_name | grep -E "${successful_response_regex}" >/dev/null; then
      echo "Log \"$2\" shows up."
      return
    fi

    sleep 1
  done
  echo "Log $2 does not show up within a timeout of ${TIMEOUT} sec"
  exit 1
}

function cleanup_and_exit() {
    local exit_code=${1:-"0"}

    kubectl -n ${TEST_NAMESPACE} delete -f deploy/cr.yaml
    kubectl -n ${TEST_NAMESPACE} delete -f deploy/flink-rbac.yaml

    kubectl delete -f deploy/flink-native-k8s-operator.yaml
    kubectl delete -f deploy/crd.yaml
    kubectl delete ns ${TEST_NAMESPACE}

    exit $exit_code
}

kubectl create ns ${TEST_NAMESPACE}
kubectl apply -f deploy/crd.yaml
# K8s operator is running in default namespace
kubectl apply -f deploy/flink-native-k8s-operator.yaml

# Apply the rbac file in correct namespace
sed "s/namespace: default/namespace: ${TEST_NAMESPACE}/" deploy/flink-rbac.yaml >deploy/flink-rbac.yaml.tmp
kubectl -n ${TEST_NAMESPACE} apply -f deploy/flink-rbac.yaml.tmp
rm -f deploy/flink-rbac.yaml.tmp

kubectl -n ${TEST_NAMESPACE} apply -f deploy/cr.yaml

for i in $(seq 1 $TIMEOUT);do
  if kubectl -n ${TEST_NAMESPACE} get deploy/${CLUSTER_ID} >/dev/null 2>&1; then
    break;
  fi
  sleep 1
done

kubectl -n ${TEST_NAMESPACE} wait --for=condition=Available --timeout=${TIMEOUT}s deploy/${CLUSTER_ID} || cleanup_and_exit 1
jm_pod_name=$(kubectl -n ${TEST_NAMESPACE} get pods --selector="app=${CLUSTER_ID},component=jobmanager" -o jsonpath='{..metadata.name}')

echo "Waiting for jobmanager pod ${jm_pod_name} ready."
kubectl -n ${TEST_NAMESPACE} wait --for=condition=Ready --timeout=${TIMEOUT}s pod/$jm_pod_name || cleanup_and_exit 1

wait_for_logs $jm_pod_name "Rest endpoint listening at"

wait_for_logs $jm_pod_name "Completed checkpoint [0-9]+ for job"

job_id=$(kubectl -n ${TEST_NAMESPACE} logs $jm_pod_name | grep -E -o 'Job [a-z0-9]+ is submitted' | awk '{print $2}')

# Kill the JobManager
echo "Kill the $jm_pod_name"
kubectl -n ${TEST_NAMESPACE} exec $jm_pod_name -- /bin/sh -c "kill 1" || cleanup_and_exit 1

# Check the new JobManager recovering from latest successful checkpoint
wait_for_logs $jm_pod_name "Restoring job $job_id from Checkpoint"
wait_for_logs $jm_pod_name "Completed checkpoint [0-9]+ for job"

echo "Triggering and waiting for the savepoint"
kubectl -n ${TEST_NAMESPACE} annotate flinkapp ${CLUSTER_ID} flinkapps.flink.k8s.io/user-control=savepoint
for i in $(seq 1 $TIMEOUT);do
  savepoint=$(kubectl -n ${TEST_NAMESPACE} get flinkapp ${CLUSTER_ID} -o jsonpath='{..status.jobStatuses[0].savepoint}' | grep "/opt/flink/volume/flink-sp")
  if [ -n "${savepoint}" ]; then
    echo ${savepoint}
    break;
  fi
  sleep 1
done
[ -z "${savepoint}" ] && cleanup_and_exit 1

echo "Successfully run the Flink Kubernetes application test"

cleanup_and_exit
