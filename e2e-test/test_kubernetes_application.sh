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

function check_logs_output {
  local pod_name=$1
  local successful_response_regex=$2
  LOG_CONTENT=$(kubectl -n ${TEST_NAMESPACE} logs $pod_name 2> /dev/null)

  # ensure the log content adapts with the successful regex
  if [[ ${LOG_CONTENT} =~ ${successful_response_regex} ]]; then
    return 0
  fi
  return 1
}

function wait_for_logs {
  local jm_pod_name=$1
  local successful_response_regex=$2
  local timeout=${3:-${TIMEOUT}}

  echo "Waiting for jobmanager pod ${jm_pod_name} ready."
  kubectl -n ${TEST_NAMESPACE} wait --for=condition=Ready --timeout=${timeout}s pod/$jm_pod_name || exit 1

  # wait or timeout until the log shows up
  echo "Waiting for log \"$2\"..."
  for i in $(seq 1 ${timeout}); do
    if check_logs_output $jm_pod_name $successful_response_regex; then
      echo "Log \"$2\" shows up."
      return
    fi

    sleep 1
  done
  echo "Log $2 does not show up within a timeout of ${timeout} sec"
  exit 1
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
  if kubectl -n ${TEST_NAMESPACE} get deploy/${CLUSTER_ID}; then
    break;
  fi
  sleep 1
done

kubectl -n ${TEST_NAMESPACE} wait --for=condition=Available --timeout=${TIMEOUT}s deploy/${CLUSTER_ID} || exit 1
jm_pod_name=$(kubectl -n ${TEST_NAMESPACE} get pods --selector="app=${CLUSTER_ID},component=jobmanager" -o jsonpath='{..metadata.name}')

wait_for_logs $jm_pod_name "Rest endpoint listening at"

wait_for_logs $jm_pod_name "Completed checkpoint 1 for job"

kubectl -n ${TEST_NAMESPACE} delete -f deploy/cr.yaml
kubectl -n ${TEST_NAMESPACE} wait --for=delete --timeout=${TIMEOUT}s deploy/${CLUSTER_ID} || exit 1
kubectl -n ${TEST_NAMESPACE} delete -f deploy/flink-rbac.yaml

kubectl delete -f deploy/flink-native-k8s-operator.yaml
kubectl delete -f deploy/crd.yaml
kubectl delete ns ${TEST_NAMESPACE}

echo "Successfully run the Flink Kubernetes application test"
