package org.apache.flink.kubernetes.operator.crd.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.fabric8.kubernetes.api.model.KubernetesResource;
import lombok.ToString;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize()
@ToString
public class FlinkApplicationSpec implements KubernetesResource {
    private String imageName;
    private String imagePullPolicy;

    private String jarURI;
    private String[] mainArgs = new String[0];
    private String entryClass;

    private int parallelism;

    private Resource jobManagerResource;
    private Resource taskManagerResource;

    private Map<String, String> flinkConfig;

    public String getImageName() {
        return imageName;
    }

    public void setImageName(String imageName) {
        this.imageName = imageName;
    }

    public String getImagePullPolicy() {
        return imagePullPolicy;
    }

    public void setImagePullPolicy(String imagePullPolicy) {
        this.imagePullPolicy = imagePullPolicy;
    }

    public String getJarURI() {
        return jarURI;
    }

    public void setJarURI(String jarURI) {
        this.jarURI = jarURI;
    }

    public String[] getMainArgs() {
        return mainArgs;
    }

    public void setMainArgs(String[] mainArgs) {
        this.mainArgs = mainArgs;
    }

    public String getEntryClass() {
        return entryClass;
    }

    public void setEntryClass(String entryClass) {
        this.entryClass = entryClass;
    }

    public int getParallelism() {
        return parallelism;
    }

    public void setParallelism(int parallelism) {
        this.parallelism = parallelism;
    }

    public Resource getJobManagerResource() {
        return jobManagerResource;
    }

    public void setJobManagerResource(Resource jobManagerResource) {
        this.jobManagerResource = jobManagerResource;
    }

    public Resource getTaskManagerResource() {
        return taskManagerResource;
    }

    public void setTaskManagerResource(Resource taskManagerResource) {
        this.taskManagerResource = taskManagerResource;
    }

    public Map<String, String> getFlinkConfig() {
        return flinkConfig;
    }

    public void setFlinkConfig(Map<String, String> flinkConfig) {
        this.flinkConfig = flinkConfig;
    }
}
