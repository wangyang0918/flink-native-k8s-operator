package org.apache.flink.kubernetes.operator.crd;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.fabric8.kubernetes.api.model.KubernetesResource;
import lombok.ToString;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize()
@ToString

public class FlinkApplicationStatus implements KubernetesResource {
    private String state;

    public void setState(String state) {
        this.state = state;
    }

    public String getState() {
        return state;
    }
}
