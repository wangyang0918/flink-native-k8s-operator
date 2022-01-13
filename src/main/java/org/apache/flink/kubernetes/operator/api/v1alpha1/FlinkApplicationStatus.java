package org.apache.flink.kubernetes.operator.api.v1alpha1;

import lombok.ToString;

@ToString
public class FlinkApplicationStatus {
    private JobStatus[] jobStatuses;

    public FlinkApplicationStatus() {}

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
