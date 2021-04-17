package freenet.node.metrics;

import java.time.*;

public class Metric {
    private final LocalDateTime ldt;
    private final String name;
    private final Integer value;

    public Metric(String name, Integer value) {
        this.name = name;
        this.value = value;
        this.ldt = LocalDateTime.now();
    }

    public LocalDateTime getTimestamp() {
        return ldt;
    }

    public String getName() {
        return name;
    }

    public Integer getValue() {
        return value;
    }
}
