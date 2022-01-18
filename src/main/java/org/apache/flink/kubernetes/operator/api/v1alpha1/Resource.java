package org.apache.flink.kubernetes.operator.api.v1alpha1;

import lombok.ToString;

@ToString
public class Resource {

    private double cpu;
    // 1024m, 1g
    private String mem;

    public double getCpu() {
        return cpu;
    }

    public void setCpu(double cpu) {
        this.cpu = cpu;
    }

    public String getMem() {
        return mem;
    }

    public void setMem(String mem) {
        this.mem = mem;
    }
}
