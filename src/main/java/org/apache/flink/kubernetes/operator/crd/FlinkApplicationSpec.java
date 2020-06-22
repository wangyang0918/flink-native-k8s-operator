package org.apache.flink.kubernetes.operator.crd;

import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.fabric8.kubernetes.api.model.KubernetesResource;

@JsonDeserialize(
    using = JsonDeserializer.None.class
)
public class FlinkApplicationSpec implements KubernetesResource {
    private String imageName;
    private String jarURI;

    public String getImageName() {
        return imageName;
    }

    public void setImageName(String imageName) {
        this.imageName = imageName;
    }

    public String getJarURI() {
        return jarURI;
    }

    public void setJarURI(String jarURI) {
        this.jarURI = jarURI;
    }

    @Override
    public String toString() {
        return "FlinkApplicationSpec{imageName=" + imageName + "}";
    }
}
