package org.apache.flink.kubernetes.operator.controller;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.fabric8.kubernetes.client.informers.cache.Lister;
import org.apache.flink.client.cli.ApplicationDeployer;
import org.apache.flink.client.deployment.ClusterClientServiceLoader;
import org.apache.flink.client.deployment.DefaultClusterClientServiceLoader;
import org.apache.flink.client.deployment.application.ApplicationConfiguration;
import org.apache.flink.client.deployment.application.cli.ApplicationClusterDeployer;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.DeploymentOptions;
import org.apache.flink.configuration.JobManagerOptions;
import org.apache.flink.configuration.PipelineOptions;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.kubernetes.configuration.KubernetesConfigOptions;
import org.apache.flink.kubernetes.operator.crd.FlinkApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class FlinkApplicationController {
    private static final Logger LOG = LoggerFactory.getLogger(FlinkApplicationController.class);
    private static final int RECONCILE_INTERVAL_MS = 3000;

    private BlockingQueue<String> workqueue;
    private SharedIndexInformer<FlinkApplication> flinkClusterInformer;
    private Lister<FlinkApplication> flinkClusterLister;
    private KubernetesClient kubernetesClient;

    public FlinkApplicationController(
            KubernetesClient kubernetesClient,
            SharedIndexInformer<FlinkApplication> flinkClusterInformer,
            String namespace) {
        this.kubernetesClient = kubernetesClient;
        this.flinkClusterLister = new Lister<>(flinkClusterInformer.getIndexer(), namespace);
        this.flinkClusterInformer = flinkClusterInformer;
        this.workqueue = new ArrayBlockingQueue<>(1024);
    }

    public void create() {
        flinkClusterInformer.addEventHandler(new ResourceEventHandler<FlinkApplication>() {
            @Override
            public void onAdd(FlinkApplication flinkApplication) {
                addFlinkApplication(flinkApplication);
            }

            @Override
            public void onUpdate(FlinkApplication flinkApplication, FlinkApplication newFlinkApplication) {
                addFlinkApplication(newFlinkApplication);
            }

            @Override
            public void onDelete(FlinkApplication flinkApplication, boolean b) {
                final String clusterId = flinkApplication.getMetadata().getName();
                LOG.info("{} is deleted, deleting flink resources", clusterId);
                kubernetesClient
                    .apps()
                    .deployments()
                    .inNamespace("default")
                    .withName(clusterId)
                    .cascading(true)
                    .delete();
            }
        });
    }

    public void run() {
        LOG.info("Starting FlinkApplication controller");

        while (true) {
            if (!flinkClusterInformer.hasSynced()) {
                continue;
            }
            try {
                LOG.info("Trying to get item from work queue...");
                if (workqueue.isEmpty()) {
                    LOG.info("Work queue is empty");
                }
                String item = workqueue.take();
                if (item.isEmpty() || (!item.contains("/"))) {
                    LOG.warn("Ignoring invalid resource key: {}", item);
                }

                // Get the FlinkApplication resource's name from key which is in format namespace/name
                String name = item.split("/")[1];
                FlinkApplication flinkApplication = flinkClusterLister.get(item.split("/")[1]);
                if (flinkApplication == null) {
                    LOG.error("FlinkApplication {} in workqueue no longer exists", name);
                    return;
                }
                LOG.info("Reconciling " + flinkApplication);
                reconcile(flinkApplication);

                Thread.sleep(RECONCILE_INTERVAL_MS);
            } catch (InterruptedException interruptedException) {
                LOG.error("Controller interrupted..");
            }
        }
    }

    /**
     * Tries to achieve the desired state for flink cluster.
     *
     * @param flinkApplication specified flink cluster
     */
    private void reconcile(FlinkApplication flinkApplication) {
        final String clusterId = flinkApplication.getMetadata().getName();
        final Deployment deployment = kubernetesClient.apps().deployments().withName(clusterId).get();
        // Create new flink cluster
        if (deployment == null) {
            final ClusterClientServiceLoader clusterClientServiceLoader = new DefaultClusterClientServiceLoader();
            final ApplicationDeployer deployer = new ApplicationClusterDeployer(clusterClientServiceLoader);
            final URI uri;
            try {
                uri = new URI(flinkApplication.getSpec().getJarURI());
            } catch (URISyntaxException e) {
                LOG.error("Error to parse jar uri {}", flinkApplication.getSpec().getJarURI(), e);
                return;
            }
            final Configuration effectiveConfig = new Configuration();
            effectiveConfig.setString(KubernetesConfigOptions.CLUSTER_ID, clusterId);
            effectiveConfig.set(DeploymentOptions.TARGET, "kubernetes-application");
            effectiveConfig.set(PipelineOptions.JARS, Collections.singletonList(uri.toString()));
            effectiveConfig.set(KubernetesConfigOptions.CONTAINER_IMAGE, flinkApplication.getSpec().getImageName());
            // TODO move it to CRD and support dynamic config options
            effectiveConfig.setString(JobManagerOptions.TOTAL_PROCESS_MEMORY.key(), "4096m");
            effectiveConfig.setString(TaskManagerOptions.TOTAL_PROCESS_MEMORY.key(), "2048m");
            final ApplicationConfiguration applicationConfiguration =
                ApplicationConfiguration.fromConfiguration(effectiveConfig);
            try {
                deployer.run(effectiveConfig, applicationConfiguration);
            } catch (Exception e) {
                LOG.error("Failed to deploy cluster", e);
            }
        } else {
            // TODO Update existing cluster
        }
    }

    private void addFlinkApplication(FlinkApplication flinkApplication) {
        LOG.info("enqueuePodSet(" + flinkApplication.getMetadata().getName() + ")");
        String item = Cache.metaNamespaceKeyFunc(flinkApplication);
        if (item != null && !item.isEmpty()) {
            LOG.info("Adding item {} to work queue", item);
            workqueue.add(item);
        }
    }
}
