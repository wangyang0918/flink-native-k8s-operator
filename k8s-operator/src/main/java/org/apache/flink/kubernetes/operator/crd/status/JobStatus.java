package org.apache.flink.kubernetes.operator.crd.status;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.fabric8.kubernetes.api.model.KubernetesResource;
import lombok.ToString;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize()
@ToString

public class JobStatus implements KubernetesResource {
    private String jobName;
    private String jobId;
    private String state;
    private String updateTime;
    private String savepointLocation;

    public JobStatus() {
    }

    public JobStatus(String jobName, String jobId, String state, String updateTime) {
        this.jobName = jobName;
        this.jobId = jobId;
        this.state = state;
        this.updateTime = updateTime;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(String updateTime) {
        this.updateTime = updateTime;
    }

    public String getSavepointLocation() {
        return savepointLocation;
    }

    public void setSavepointLocation(String savepointLocation) {
        this.savepointLocation = savepointLocation;
    }
}
