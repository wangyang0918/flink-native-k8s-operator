package org.apache.flink.kubernetes.operator.controller;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.fabric8.kubernetes.client.informers.cache.Lister;

import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.client.cli.ApplicationDeployer;
import org.apache.flink.client.deployment.ClusterClientServiceLoader;
import org.apache.flink.client.deployment.DefaultClusterClientServiceLoader;
import org.apache.flink.client.deployment.application.ApplicationConfiguration;
import org.apache.flink.client.deployment.application.cli.ApplicationClusterDeployer;
import org.apache.flink.client.program.ClusterClient;
import org.apache.flink.client.program.rest.RestClusterClient;
import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.CoreOptions;
import org.apache.flink.configuration.DeploymentOptions;
import org.apache.flink.configuration.GlobalConfiguration;
import org.apache.flink.configuration.JobManagerOptions;
import org.apache.flink.configuration.PipelineOptions;
import org.apache.flink.configuration.RestOptions;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.kubernetes.configuration.KubernetesConfigOptions;
import org.apache.flink.kubernetes.operator.crd.DoneableFlinkApplication;
import org.apache.flink.kubernetes.operator.crd.FlinkApplication;
import org.apache.flink.kubernetes.operator.crd.FlinkApplicationList;
import org.apache.flink.kubernetes.operator.crd.spec.FlinkApplicationSpec;
import org.apache.flink.kubernetes.operator.crd.status.FlinkApplicationStatus;
import org.apache.flink.kubernetes.operator.crd.status.JobStatus;
import org.apache.flink.runtime.client.JobStatusMessage;
import org.apache.flink.runtime.highavailability.nonha.standalone.StandaloneClientHAServices;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FlinkApplicationController {
    private static final Logger LOG = LoggerFactory.getLogger(FlinkApplicationController.class);
    private static final int RECONCILE_INTERVAL_MS = 3000;
    private static final String KUBERNETES_APP_TARGET = "kubernetes-application";

    private final KubernetesClient kubernetesClient;
    private final MixedOperation<FlinkApplication, FlinkApplicationList, DoneableFlinkApplication, Resource<FlinkApplication, DoneableFlinkApplication>> flinkAppK8sClient;
    private final SharedIndexInformer<FlinkApplication> flinkAppInformer;
    private final Lister<FlinkApplication> flinkClusterLister;

    private final BlockingQueue<String> workqueue;
    private final Map<String, Tuple2<FlinkApplication, Configuration>> flinkApps;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public FlinkApplicationController(
            KubernetesClient kubernetesClient,
            MixedOperation<FlinkApplication, FlinkApplicationList, DoneableFlinkApplication, Resource<FlinkApplication, DoneableFlinkApplication>> flinkAppK8sClient,
            SharedIndexInformer<FlinkApplication> flinkAppInformer,
            String namespace) {
        this.kubernetesClient = kubernetesClient;
        this.flinkAppK8sClient = flinkAppK8sClient;
        this.flinkClusterLister = new Lister<>(flinkAppInformer.getIndexer(), namespace);
        this.flinkAppInformer = flinkAppInformer;

        this.workqueue = new ArrayBlockingQueue<>(1024);
        this.flinkApps = new HashMap<>();
    }

    public void create() {
        flinkAppInformer.addEventHandler(new ResourceEventHandler<FlinkApplication>() {
            @Override
            public void onAdd(FlinkApplication flinkApplication) {
                addToWorkQueue(flinkApplication);
            }

            @Override
            public void onUpdate(FlinkApplication flinkApplication, FlinkApplication newFlinkApplication) {
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
                    .cascading(true)
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
                if (item.isEmpty() || (!item.contains("/"))) {
                    LOG.warn("Ignoring invalid resource item: {}", item);
                }

                // Get the FlinkApplication resource's name from key which is in format namespace/name
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
        final Deployment deployment = kubernetesClient.apps().deployments().withName(clusterId).get();

        final Configuration effectiveConfig;
        try {
            effectiveConfig = getEffectiveConfig(namespace, clusterId, flinkApp.getSpec());
        } catch (Exception e) {
            LOG.error("Failed to load configuration", e);
            return;
        }

        // Create new Flink application
        if (!flinkApps.containsKey(clusterId) && deployment == null) {
            final ClusterClientServiceLoader clusterClientServiceLoader = new DefaultClusterClientServiceLoader();
            final ApplicationDeployer deployer = new ApplicationClusterDeployer(clusterClientServiceLoader);

            final ApplicationConfiguration applicationConfiguration =
                new ApplicationConfiguration(flinkApp.getSpec().getMainArgs(), flinkApp.getSpec().getEntryClass());
            try {
                deployer.run(effectiveConfig, applicationConfiguration);
            } catch (Exception e) {
                LOG.error("Failed to deploy cluster {}", clusterId, e);
            }
            flinkApps.put(clusterId, new Tuple2<>(flinkApp, effectiveConfig));
        } else {
            if (!flinkApps.containsKey(clusterId)) {
                LOG.info("Recovering {}", clusterId);
                flinkApps.put(clusterId, new Tuple2<>(flinkApp, effectiveConfig));
            }
            // TODO Update existing cluster
        }
    }

    private Configuration getEffectiveConfig(String namespace, String clusterId, FlinkApplicationSpec spec) throws Exception {
        final String flinkConfDir = System.getenv().get(ConfigConstants.ENV_FLINK_CONF_DIR);
        final Configuration effectiveConfig;
        if (flinkConfDir != null) {
            effectiveConfig = GlobalConfiguration.loadConfiguration(flinkConfDir);
        } else {
            effectiveConfig = new Configuration();
        }

        // Basic config options
        final URI uri = new URI(spec.getJarURI());
        effectiveConfig.setString(KubernetesConfigOptions.NAMESPACE, namespace);
        effectiveConfig.setString(KubernetesConfigOptions.CLUSTER_ID, clusterId);
        effectiveConfig.set(DeploymentOptions.TARGET, KUBERNETES_APP_TARGET);

        // Image
        if (spec.getImageName() != null && !spec.getImageName().isEmpty()) {
            effectiveConfig.set(KubernetesConfigOptions.CONTAINER_IMAGE, spec.getImageName());
        }
        if (spec.getImagePullPolicy() != null && !spec.getImagePullPolicy().isEmpty()) {
            effectiveConfig.set(
                KubernetesConfigOptions.CONTAINER_IMAGE_PULL_POLICY,
                KubernetesConfigOptions.ImagePullPolicy.valueOf(spec.getImagePullPolicy()));
        }

        // Jars
        effectiveConfig.set(PipelineOptions.JARS, Collections.singletonList(uri.toString()));

        // Parallelism and Resource
        if (spec.getParallelism() > 0) {
            effectiveConfig.set(CoreOptions.DEFAULT_PARALLELISM, spec.getParallelism());
        }
        if (spec.getJobManagerResource() != null) {
            effectiveConfig.setString(JobManagerOptions.TOTAL_PROCESS_MEMORY.key(), spec.getJobManagerResource().getMem());
            effectiveConfig.set(KubernetesConfigOptions.JOB_MANAGER_CPU, spec.getJobManagerResource().getCpu());
        }
        if (spec.getTaskManagerResource() != null) {
            effectiveConfig.setString(TaskManagerOptions.TOTAL_PROCESS_MEMORY.key(), spec.getTaskManagerResource().getMem());
            effectiveConfig.set(KubernetesConfigOptions.TASK_MANAGER_CPU, spec.getTaskManagerResource().getCpu());
        }

        // Dynamic configuration
        if (spec.getFlinkConfig() != null && !spec.getFlinkConfig().isEmpty()) {
            spec.getFlinkConfig().forEach(effectiveConfig::setString);
        }

        return effectiveConfig;
    }

    private void addToWorkQueue(FlinkApplication flinkApplication) {
        String item = Cache.metaNamespaceKeyFunc(flinkApplication);
        if (item != null && !item.isEmpty()) {
            LOG.info("Adding item {} to work queue", item);
            workqueue.add(item);
        }
    }

    private ClusterClient<String> getRestClusterClient(Configuration config) throws Exception {
        final String clusterId = config.get(KubernetesConfigOptions.CLUSTER_ID);
        final String namespace = config.get(KubernetesConfigOptions.NAMESPACE);
        final int port = config.getInteger(RestOptions.PORT);
        final String restServerAddress = String.format("http://%s-rest.%s:%s", clusterId, namespace, port);
        return new RestClusterClient<>(
            config,
            clusterId,
            new StandaloneClientHAServices(restServerAddress));
    }

    private class JobStatusUpdater implements Runnable {
        @Override
        public void run() {
            LOG.info("Starting JobStatusUpdater");
            while (true) {
                for (Tuple2<FlinkApplication, Configuration> flinkApp : flinkApps.values()) {
                    try {
                        final ClusterClient<String> clusterClient = getRestClusterClient(flinkApp.f1);
                        final CompletableFuture<Collection<JobStatusMessage>> jobDetailsFuture = clusterClient.listJobs();
                        final List<JobStatus> jobStatusList = new ArrayList<>();
                        jobDetailsFuture.get().forEach(
                            status -> {
                                LOG.debug("JobStatus for {}: ", clusterClient.getClusterId(), status);
                                jobStatusList.add(new JobStatus(
                                    status.getJobName(),
                                    status.getJobId().toHexString(),
                                    status.getJobState().name(),
                                    String.valueOf(System.currentTimeMillis())));
                            });
                        flinkApp.f0.setStatus(new FlinkApplicationStatus(jobStatusList.toArray(new JobStatus[0])));
                        flinkAppK8sClient.createOrReplace(flinkApp.f0);
                    } catch (Exception e) {
                        LOG.warn("Failed to list jobs for {}", flinkApp.f0.getMetadata().getName(), e);
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
