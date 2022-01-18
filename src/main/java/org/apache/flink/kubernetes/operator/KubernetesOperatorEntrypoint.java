package org.apache.flink.kubernetes.operator;

import org.apache.flink.kubernetes.operator.api.v1alpha1.FlinkApplication;
import org.apache.flink.kubernetes.operator.api.v1alpha1.FlinkApplicationList;
import org.apache.flink.kubernetes.operator.controller.FlinkApplicationController;

import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Future;

/** Main Class for Flink native k8s operator. */
public class KubernetesOperatorEntrypoint {
    private static final Logger LOG = LoggerFactory.getLogger(KubernetesOperatorEntrypoint.class);

    public static void main(String[] args) {
        try (KubernetesClient kubeClient = new DefaultKubernetesClient()) {
            String namespace = kubeClient.getNamespace();
            if (namespace == null) {
                LOG.info("No namespace found via config, assuming default.");
                namespace = "default";
            }

            LOG.info("Using namespace : " + namespace);

            final SharedInformerFactory informerFactory = kubeClient.informers();

            final SharedIndexInformer<FlinkApplication> flinkAppInformer =
                    informerFactory.sharedIndexInformerFor(FlinkApplication.class, 0);
            final MixedOperation<FlinkApplication, FlinkApplicationList, Resource<FlinkApplication>>
                    flinkAppK8sClient =
                            kubeClient.customResources(
                                    FlinkApplication.class, FlinkApplicationList.class);

            final FlinkApplicationController flinkApplicationController =
                    new FlinkApplicationController(
                            kubeClient, flinkAppK8sClient, flinkAppInformer, namespace);

            informerFactory.addSharedInformerEventListener(
                    exception -> LOG.error("Exception occurred, but caught", exception));
            final Future<Void> startInformersFuture = informerFactory.startAllRegisteredInformers();
            startInformersFuture.get();

            flinkApplicationController.run();
        } catch (Exception exception) {
            LOG.error("Kubernetes Client Exception : ", exception);
        }
    }
}
