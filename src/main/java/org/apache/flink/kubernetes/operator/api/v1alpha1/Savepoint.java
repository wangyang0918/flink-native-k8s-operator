package org.apache.flink.kubernetes.operator.api.v1alpha1;

import lombok.ToString;

@ToString
public class Savepoint {
    private String triggerTimestamp;
    private String savepointLocation;

    public Savepoint(String triggerTimestamp, String savepointLocation) {
        this.triggerTimestamp = triggerTimestamp;
        this.savepointLocation = savepointLocation;
    }

    public Savepoint() {}

    public String getSavepointLocation() {
        return savepointLocation;
    }

    public void setSavepointLocation(String savepointLocation) {
        this.savepointLocation = savepointLocation;
    }

    public String getTriggerTimestamp() {
        return triggerTimestamp;
    }

    public void setTriggerTimestamp(String triggerTimestamp) {
        this.triggerTimestamp = triggerTimestamp;
    }
}
