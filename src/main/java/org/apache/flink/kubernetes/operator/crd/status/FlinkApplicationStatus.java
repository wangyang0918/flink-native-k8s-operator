package org.apache.flink.kubernetes.operator.crd.status;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.fabric8.kubernetes.api.model.KubernetesResource;
import lombok.ToString;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize()
@ToString

public class FlinkApplicationStatus implements KubernetesResource {
    private JobStatus[] jobStatuses;

    public FlinkApplicationStatus() {
    }

    public FlinkApplicationStatus(JobStatus[] jobStatuses) {
        this.jobStatuses = jobStatuses;
    }

    public JobStatus[] getJobStatuses() {
        return jobStatuses;
    }

    public void setJobStatuses(JobStatus[] jobStatuses) {
        this.jobStatuses = jobStatuses;
    }
}
