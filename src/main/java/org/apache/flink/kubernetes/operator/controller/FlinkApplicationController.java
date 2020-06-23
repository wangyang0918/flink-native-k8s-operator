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
import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.CoreOptions;
import org.apache.flink.configuration.DeploymentOptions;
import org.apache.flink.configuration.GlobalConfiguration;
import org.apache.flink.configuration.JobManagerOptions;
import org.apache.flink.configuration.PipelineOptions;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.kubernetes.configuration.KubernetesConfigOptions;
import org.apache.flink.kubernetes.operator.crd.FlinkApplication;
import org.apache.flink.kubernetes.operator.crd.spec.FlinkApplicationSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class FlinkApplicationController {
    private static final Logger LOG = LoggerFactory.getLogger(FlinkApplicationController.class);
    private static final int RECONCILE_INTERVAL_MS = 3000;
    private static final String KUBERNETES_APP_TARGET = "kubernetes-application";

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
                final String namespace = flinkApplication.getMetadata().getNamespace();
                LOG.info("{} is deleted, destroying flink resources", clusterId);
                kubernetesClient
                    .apps()
                    .deployments()
                    .inNamespace(namespace)
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
                    return;
                }
                LOG.info("Reconciling " + flinkApplication);
                reconcile(flinkApplication);

                Thread.sleep(RECONCILE_INTERVAL_MS);
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
        // Create new Flink application
        if (deployment == null) {
            final ClusterClientServiceLoader clusterClientServiceLoader = new DefaultClusterClientServiceLoader();
            final ApplicationDeployer deployer = new ApplicationClusterDeployer(clusterClientServiceLoader);

            final Configuration effectiveConfig;
            try {
                effectiveConfig = getEffectiveConfig(namespace, clusterId, flinkApp.getSpec());
            } catch (Exception e) {
                LOG.error("Failed to load configuration", e);
                return;
            }

            final ApplicationConfiguration applicationConfiguration =
                new ApplicationConfiguration(flinkApp.getSpec().getMainArgs(), flinkApp.getSpec().getEntryClass());
            try {
                deployer.run(effectiveConfig, applicationConfiguration);
            } catch (Exception e) {
                LOG.error("Failed to deploy cluster {}", clusterId, e);
            }
        } else {
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

    private void addFlinkApplication(FlinkApplication flinkApplication) {
        LOG.info("enqueueFlinkApp(" + flinkApplication.getMetadata().getName() + ")");
        String item = Cache.metaNamespaceKeyFunc(flinkApplication);
        if (item != null && !item.isEmpty()) {
            LOG.info("Adding item {} to work queue", item);
            workqueue.add(item);
        }
    }
}
