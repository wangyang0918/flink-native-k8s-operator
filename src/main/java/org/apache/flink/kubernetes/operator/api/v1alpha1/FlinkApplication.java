package org.apache.flink.kubernetes.operator.api.v1alpha1;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.Singular;
import io.fabric8.kubernetes.model.annotation.Version;

@Version("v1alpha1")
@Group("flink.k8s.io")
@Singular("flinkapplication")
@Plural("flinkapplications")
public class FlinkApplication extends CustomResource<FlinkApplicationSpec, FlinkApplicationStatus>
        implements Namespaced {
    private FlinkApplicationSpec spec;
    private FlinkApplicationStatus status;

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
        return "FlinkApplication{"
                + "apiVersion='"
                + getApiVersion()
                + "'"
                + ", metadata="
                + getMetadata()
                + ", spec="
                + spec
                + ", status="
                + status
                + "}";
    }

    @Override
    public ObjectMeta getMetadata() {
        return super.getMetadata();
    }
}
