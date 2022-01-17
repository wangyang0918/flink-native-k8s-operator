package org.apache.flink.kubernetes.operator.controller;

import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.client.cli.ApplicationDeployer;
import org.apache.flink.client.deployment.ClusterClientServiceLoader;
import org.apache.flink.client.deployment.DefaultClusterClientServiceLoader;
import org.apache.flink.client.deployment.application.ApplicationConfiguration;
import org.apache.flink.client.deployment.application.cli.ApplicationClusterDeployer;
import org.apache.flink.client.program.ClusterClient;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.RestOptions;
import org.apache.flink.kubernetes.operator.api.v1alpha1.FlinkApplication;
import org.apache.flink.kubernetes.operator.api.v1alpha1.FlinkApplicationList;
import org.apache.flink.kubernetes.operator.api.v1alpha1.FlinkApplicationStatus;
import org.apache.flink.kubernetes.operator.api.v1alpha1.JobStatus;
import org.apache.flink.kubernetes.operator.utils.Constants;
import org.apache.flink.kubernetes.operator.utils.FlinkUtils;
import org.apache.flink.kubernetes.operator.utils.KubernetesUtils;
import org.apache.flink.runtime.client.JobStatusMessage;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressRuleValueBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRule;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.fabric8.kubernetes.client.informers.cache.Lister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

import static org.apache.flink.kubernetes.operator.utils.Constants.FLINK_NATIVE_K8S_OPERATOR_NAME;

public class FlinkApplicationController {
    private static final Logger LOG = LoggerFactory.getLogger(FlinkApplicationController.class);
    private static final int RECONCILE_INTERVAL_MS = 3000;

    private final KubernetesClient kubernetesClient;
    private final MixedOperation<FlinkApplication, FlinkApplicationList, Resource<FlinkApplication>>
            flinkAppK8sClient;
    private final SharedIndexInformer<FlinkApplication> flinkAppInformer;
    private final Lister<FlinkApplication> flinkClusterLister;

    private final BlockingQueue<String> workqueue;
    private final Map<String, Tuple2<FlinkApplication, Configuration>> flinkApps;
    private final Map<String, String> savepointLocation;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private final String operatorNamespace;

    public FlinkApplicationController(
            KubernetesClient kubernetesClient,
            MixedOperation<FlinkApplication, FlinkApplicationList, Resource<FlinkApplication>>
                    flinkAppK8sClient,
            SharedIndexInformer<FlinkApplication> flinkAppInformer,
            String namespace) {
        this.kubernetesClient = kubernetesClient;
        this.flinkAppK8sClient = flinkAppK8sClient;
        this.flinkClusterLister = new Lister<>(flinkAppInformer.getIndexer(), namespace);
        this.flinkAppInformer = flinkAppInformer;
        this.operatorNamespace = namespace;

        this.workqueue = new ArrayBlockingQueue<>(1024);
        this.flinkApps = new ConcurrentHashMap<>();
        this.savepointLocation = new HashMap<>();
    }

    public void create() {
        flinkAppInformer.addEventHandler(
                new ResourceEventHandler<FlinkApplication>() {
                    @Override
                    public void onAdd(FlinkApplication flinkApplication) {
                        addToWorkQueue(flinkApplication);
                    }

                    @Override
                    public void onUpdate(
                            FlinkApplication flinkApplication,
                            FlinkApplication newFlinkApplication) {
                        addToWorkQueue(newFlinkApplication);
                    }

                    @Override
                    public void onDelete(FlinkApplication flinkApplication, boolean b) {
                        final String clusterId = flinkApplication.getMetadata().getName();
                        final String namespace = flinkApplication.getMetadata().getNamespace();
                        LOG.info("{} is deleted, destroying flink resources", clusterId);
                        kubernetesClient
                                .apps()
                                .deployments()
                                .inNamespace(namespace)
                                .withName(clusterId)
                                .delete();
                        flinkApps.remove(clusterId);
                    }
                });
    }

    public void run() {
        LOG.info("Starting FlinkApplication controller");
        executorService.submit(new JobStatusUpdater());

        while (true) {
            if (!flinkAppInformer.hasSynced()) {
                continue;
            }
            try {
                LOG.info("Trying to get item from work queue");
                if (workqueue.isEmpty()) {
                    LOG.info("Work queue is empty");
                }
                String item = workqueue.take();
                if ((!item.contains("/"))) {
                    LOG.warn("Ignoring invalid resource item: {}", item);
                }

                // Get the FlinkApplication resource's name from key which is in format
                // namespace/name
                String name = item.split("/")[1];
                FlinkApplication flinkApplication = flinkClusterLister.get(item.split("/")[1]);
                if (flinkApplication == null) {
                    LOG.error("FlinkApplication {} in work queue no longer exists", name);
                    continue;
                }
                LOG.info("Reconciling " + flinkApplication);
                reconcile(flinkApplication);

            } catch (InterruptedException interruptedException) {
                LOG.error("Controller interrupted");
            }
        }
    }

    /**
     * Tries to achieve the desired state for flink cluster.
     *
     * @param flinkApp specified flink cluster
     */
    private void reconcile(FlinkApplication flinkApp) {
        final String namespace = flinkApp.getMetadata().getNamespace();
        final String clusterId = flinkApp.getMetadata().getName();
        final Deployment deployment =
                kubernetesClient
                        .apps()
                        .deployments()
                        .inNamespace(namespace)
                        .withName(clusterId)
                        .get();

        final Configuration effectiveConfig;
        try {
            effectiveConfig =
                    FlinkUtils.getEffectiveConfig(namespace, clusterId, flinkApp.getSpec());
            LOG.info("Effective configuration: {}", effectiveConfig);
        } catch (Exception e) {
            LOG.error("Failed to load configuration", e);
            return;
        }

        // Create new Flink application
        if (!flinkApps.containsKey(clusterId) && deployment == null) {
            // Deploy application
            final ClusterClientServiceLoader clusterClientServiceLoader =
                    new DefaultClusterClientServiceLoader();
            final ApplicationDeployer deployer =
                    new ApplicationClusterDeployer(clusterClientServiceLoader);

            final ApplicationConfiguration applicationConfiguration =
                    new ApplicationConfiguration(
                            flinkApp.getSpec().getMainArgs(), flinkApp.getSpec().getEntryClass());
            try {
                deployer.run(effectiveConfig, applicationConfiguration);
            } catch (Exception e) {
                LOG.error("Failed to deploy cluster {}", clusterId, e);
            }

            flinkApps.put(clusterId, new Tuple2<>(flinkApp, effectiveConfig));

            updateIngress();
        } else {
            if (!flinkApps.containsKey(clusterId)) {
                LOG.info("Recovering {}", clusterId);
                flinkApps.put(clusterId, new Tuple2<>(flinkApp, effectiveConfig));
                return;
            }
            // Flink app is deleted externally
            if (deployment == null) {
                LOG.warn("{} is delete externally.", clusterId);
                flinkApps.remove(clusterId);
                return;
            }

            FlinkApplication oldFlinkApp = flinkApps.get(clusterId).f0;

            // Trigger a new savepoint
            triggerSavepoint(oldFlinkApp, flinkApp, effectiveConfig);

            // TODO support more fields updating, e.g. image, resources
        }
    }

    private void updateIngress() {
        final List<IngressRule> ingressRules = new ArrayList<>();
        for (Tuple2<FlinkApplication, Configuration> entry : flinkApps.values()) {
            final FlinkApplication flinkApp = entry.f0;
            final String clusterId = flinkApp.getMetadata().getName();
            final int restPort = entry.f1.getInteger(RestOptions.PORT);

            final String ingressHost = clusterId + Constants.INGRESS_SUFFIX;
            ingressRules.add(
                    new IngressRule(
                            ingressHost,
                            new HTTPIngressRuleValueBuilder()
                                    .addNewPath()
                                    .withPathType("ImplementationSpecific")
                                    .withNewBackend()
                                    .withNewService()
                                    .withName(clusterId + Constants.REST_SVC_NAME_SUFFIX)
                                    .withNewPort()
                                    .withNumber(restPort)
                                    .endPort()
                                    .endService()
                                    .endBackend()
                                    .endPath()
                                    .build()));
        }
        final Ingress ingress =
                new IngressBuilder()
                        .withApiVersion(Constants.INGRESS_API_VERSION)
                        .withNewMetadata()
                        .withName(FLINK_NATIVE_K8S_OPERATOR_NAME)
                        .endMetadata()
                        .withNewSpec()
                        .withRules(ingressRules)
                        .endSpec()
                        .build();
        // Get operator deploy
        final Deployment deployment =
                kubernetesClient
                        .apps()
                        .deployments()
                        .inNamespace(operatorNamespace)
                        .withName(FLINK_NATIVE_K8S_OPERATOR_NAME)
                        .get();
        if (deployment == null) {
            LOG.warn("Could not find deployment {}", FLINK_NATIVE_K8S_OPERATOR_NAME);
        } else {
            KubernetesUtils.setOwnerReference(deployment, Collections.singletonList(ingress));
        }
        LOG.info(ingress.toString());
        kubernetesClient.resourceList(ingress).inNamespace(operatorNamespace).createOrReplace();
    }

    private void triggerSavepoint(
            FlinkApplication oldFlinkApp,
            FlinkApplication newFlinkApp,
            Configuration effectiveConfig) {
        final int generation = newFlinkApp.getSpec().getSavepointGeneration();
        if (generation > oldFlinkApp.getSpec().getSavepointGeneration()) {
            try (ClusterClient<String> clusterClient =
                    FlinkUtils.getRestClusterClient(effectiveConfig)) {
                final CompletableFuture<Collection<JobStatusMessage>> jobDetailsFuture =
                        clusterClient.listJobs();
                jobDetailsFuture
                        .get()
                        .forEach(
                                status -> {
                                    LOG.debug(
                                            "JobStatus for {}: {}",
                                            clusterClient.getClusterId(),
                                            status);
                                    clusterClient
                                            .triggerSavepoint(status.getJobId(), null)
                                            .thenAccept(
                                                    path ->
                                                            savepointLocation.put(
                                                                    status.getJobId().toString(),
                                                                    path))
                                            .join();
                                });
            } catch (Exception e) {
                LOG.warn("Failed to trigger a new savepoint with generation {}", generation);
            }
        }
    }

    private void addToWorkQueue(FlinkApplication flinkApplication) {
        String item = Cache.metaNamespaceKeyFunc(flinkApplication);
        if (item != null && !item.isEmpty()) {
            LOG.info("Adding item {} to work queue", item);
            workqueue.add(item);
        }
    }

    private class JobStatusUpdater implements Runnable {
        @Override
        public void run() {
            LOG.info("Starting JobStatusUpdater");
            while (true) {
                for (Tuple2<FlinkApplication, Configuration> flinkApp : flinkApps.values()) {
                    try (final ClusterClient<String> clusterClient =
                            FlinkUtils.getRestClusterClient(flinkApp.f1)) {
                        final CompletableFuture<Collection<JobStatusMessage>> jobDetailsFuture =
                                clusterClient.listJobs();
                        final List<JobStatus> jobStatusList = new ArrayList<>();
                        jobDetailsFuture
                                .get()
                                .forEach(
                                        status -> {
                                            LOG.debug(
                                                    "JobStatus for {}: {}",
                                                    clusterClient.getClusterId(),
                                                    status);
                                            final String jobId = status.getJobId().toString();
                                            final JobStatus jobStatus =
                                                    new JobStatus(
                                                            status.getJobName(),
                                                            jobId,
                                                            status.getJobState().name(),
                                                            String.valueOf(
                                                                    System.currentTimeMillis()));
                                            if (savepointLocation.containsKey(jobId)) {
                                                jobStatus.setSavepointLocation(
                                                        savepointLocation.get(jobId));
                                            }
                                            jobStatusList.add(jobStatus);
                                        });
                        flinkApp.f0.setStatus(
                                new FlinkApplicationStatus(
                                        jobStatusList.toArray(new JobStatus[0])));
                        flinkAppK8sClient
                                .inNamespace(flinkApp.f0.getMetadata().getNamespace())
                                .createOrReplace(flinkApp.f0);
                    } catch (Exception e) {
                        flinkApp.f0.setStatus(new FlinkApplicationStatus());
                        LOG.warn(
                                "Failed to list jobs for {}",
                                flinkApp.f0.getMetadata().getName(),
                                e);
                    }
                }

                try {
                    Thread.sleep(RECONCILE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    LOG.error("JobStatusUpdater interrupt");
                }
            }
        }
    }
}
