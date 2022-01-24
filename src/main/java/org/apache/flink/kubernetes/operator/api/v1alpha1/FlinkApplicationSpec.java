package org.apache.flink.kubernetes.operator.api.v1alpha1;

import lombok.ToString;

import java.util.Map;

@ToString
public class FlinkApplicationSpec {
    private String imageName;
    private String imagePullPolicy;

    private String jarURI;
    private String[] mainArgs = new String[0];
    private String entryClass;

    private int parallelism;

    private Resource jobManagerResource;
    private Resource taskManagerResource;

    private String fromSavepoint;
    private boolean allowNonRestoredState = false;
    private String savepointsDir;
    private int savepointGeneration;

    private Map<String, String> flinkConfig;

    private Object podTemplate;

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

    public String getFromSavepoint() {
        return fromSavepoint;
    }

    public void setFromSavepoint(String fromSavepoint) {
        this.fromSavepoint = fromSavepoint;
    }

    public boolean isAllowNonRestoredState() {
        return allowNonRestoredState;
    }

    public void setAllowNonRestoredState(boolean allowNonRestoredState) {
        this.allowNonRestoredState = allowNonRestoredState;
    }

    public String getSavepointsDir() {
        return savepointsDir;
    }

    public void setSavepointsDir(String savepointsDir) {
        this.savepointsDir = savepointsDir;
    }

    public int getSavepointGeneration() {
        return savepointGeneration;
    }

    public void setSavepointGeneration(int savepointGeneration) {
        this.savepointGeneration = savepointGeneration;
    }

    public void setPodTemplate(Object podTemplate) {
        this.podTemplate = podTemplate;
    }

    public Object getPodTemplate() {
        return podTemplate;
    }
}
