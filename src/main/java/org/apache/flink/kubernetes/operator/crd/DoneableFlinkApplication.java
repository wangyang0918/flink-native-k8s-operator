package org.apache.flink.kubernetes.operator.crd;

import io.fabric8.kubernetes.api.builder.Function;
import io.fabric8.kubernetes.client.CustomResourceDoneable;


public class DoneableFlinkApplication extends CustomResourceDoneable<FlinkApplication> {
    public DoneableFlinkApplication(FlinkApplication resource, Function function) { super(resource, function); }
}
