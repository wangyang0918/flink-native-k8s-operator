package org.apache.flink.kubernetes.operator;

import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import org.apache.flink.kubernetes.operator.controller.FlinkApplicationController;
import org.apache.flink.kubernetes.operator.crd.FlinkApplication;
import org.apache.flink.kubernetes.operator.crd.FlinkApplicationList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main Class for Flink native k8s operator.
 */
public class KubernetesOperatorEntrypoint {
	private static final Logger LOG = LoggerFactory.getLogger(KubernetesOperatorEntrypoint.class);

    public static void main(String args[]) {
        try (KubernetesClient client = new DefaultKubernetesClient()) {
            String namespace = client.getNamespace();
            if (namespace == null) {
                LOG.info("No namespace found via config, assuming default.");
                namespace = "default";
            }

            LOG.info("Using namespace : " + namespace);

            CustomResourceDefinitionContext crdContext = new CustomResourceDefinitionContext.Builder()
                    .withVersion("v1alpha1")
                    .withScope("Namespaced")
                    .withGroup("flink.k8s.io")
                    .withPlural("flinkapplications")
                    .build();

            final SharedInformerFactory informerFactory = client.informers();

            final SharedIndexInformer<FlinkApplication> informer = informerFactory.sharedIndexInformerForCustomResource(
            	crdContext,
	            FlinkApplication.class,
	            FlinkApplicationList.class,
	            10 * 60 * 1000);
            FlinkApplicationController flinkApplicationController = new FlinkApplicationController(
            	client,
	            informer,
	            namespace);

            flinkApplicationController.create();
            informerFactory.startAllRegisteredInformers();
            informerFactory.addSharedInformerEventListener(
            	exception -> LOG.error("Exception occurred, but caught", exception));

            flinkApplicationController.run();
        } catch (KubernetesClientException exception) {
            LOG.error("Kubernetes Client Exception : {}", exception);
        }
    }
}
