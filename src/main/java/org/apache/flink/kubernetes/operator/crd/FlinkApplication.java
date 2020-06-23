package org.apache.flink.kubernetes.operator.crd;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.CustomResource;
import org.apache.flink.kubernetes.operator.crd.spec.FlinkApplicationSpec;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize()
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
