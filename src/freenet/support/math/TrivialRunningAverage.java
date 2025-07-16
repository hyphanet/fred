package freenet.support.math;

import java.util.concurrent.atomic.AtomicReference;

public final class TrivialRunningAverage implements RunningAverage, Cloneable {

    private final AtomicReference<Data> data = new AtomicReference<>();

    public TrivialRunningAverage(TrivialRunningAverage other) {
        data.set(other.data.get());
    }

    public TrivialRunningAverage() {
        data.set(new Data(0, 0));
    }

    @Override
    public long countReports() {
        return data.get().reports;
    }

    public double totalValue() {
        return data.get().total;
    }

    @Override
    public double currentValue() {
        return data.get().getRunningAverage();
    }

    @Override
    public void report(double d) {
        data.updateAndGet(data -> data.updated(d));
    }

    @Override
    public void report(long d) {
        report((double) d);
    }

    @Override
    public double valueIfReported(double r) {
        return data.get().updated(r).getRunningAverage();
    }

    @Override
    public TrivialRunningAverage clone() {
        return new TrivialRunningAverage(this);
    }

    private static class Data {
        private final long reports;
        private final double total;

        Data(long reports, double total) {
            this.reports = reports;
            this.total = total;
        }

        Data updated(double d) {
            return new Data(reports + 1, d + total);
        }

        double getRunningAverage() {
            return total / reports;
        }
    }

}
