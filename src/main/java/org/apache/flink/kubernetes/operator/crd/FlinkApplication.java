package org.apache.flink.kubernetes.operator.crd;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.CustomResource;

public class FlinkApplication extends CustomResource {
    public FlinkApplicationSpec getSpec() {
        return spec;
    }

    public void setSpec(FlinkApplicationSpec spec) {
        this.spec = spec;
    }

    public FlinkApplicationStatus getStatus() {
        return status;
    }

    public void setStatus(FlinkApplicationStatus status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "FlinkApplication{"+
                "apiVersion='" + getApiVersion() + "'" +
                ", metadata=" + getMetadata() +
                ", spec=" + spec +
                ", status=" + status +
                "}";
    }

    private FlinkApplicationSpec spec;
    private FlinkApplicationStatus status;

    @Override
    public ObjectMeta getMetadata() {
        return super.getMetadata();
    }
}
