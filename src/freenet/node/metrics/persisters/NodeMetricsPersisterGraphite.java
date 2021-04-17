package freenet.node.metrics.persisters;

import freenet.node.metrics.*;
import freenet.support.*;

import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;

public class NodeMetricsPersisterGraphite implements Runnable {
    private final static int DEFAULT_INTERVAL = 10000;
    private final Ticker ticker;
    List<MetricsProvider> mps;
    private final GraphitePersister persister = new GraphitePersister("127.0.0.1", 2003);

    public NodeMetricsPersisterGraphite(List<MetricsProvider> mps, Ticker ticker) {
        this.mps = mps;
        this.ticker = ticker;
    }

    private void scheduleNext() {
        scheduleNext(DEFAULT_INTERVAL);
    }

    private void scheduleNext(int interval) {
        ticker.queueTimedJob(
            this,
            this.getClass().getName(),
            interval,
            false,
            true
        );
    }

    public void start() {
        scheduleNext(0);
    }

    @Override
    public void run() {
        for (MetricsProvider mp : mps) {
            persister.persist(mp.getMetrics());
        }

        scheduleNext();
    }

    interface Persister {
        void persist(List<Metric> metrics);
    }

    static class GraphitePersister implements Persister {
        private final String domain;
        private final int port;

        public GraphitePersister(String domain, int port) {
            this.domain = domain;
            this.port = port;
        }

        public void persist(List<Metric> metrics) {
            if (metrics.size() == 0) {
                return;
            }

            try {
                Socket socket = new Socket(this.domain, this.port);
                OutputStream s = socket.getOutputStream();
                PrintWriter out = new PrintWriter(s, true);

                for (Metric metric : metrics) {
                    out.printf(
                        "%s %s %s%n",
                        metric.getName(),
                        metric.getValue(),
                        metric.getTimestamp()
                            .atZone(ZoneId.systemDefault())
                            .toEpochSecond()
                    );
                }
                out.close();
                socket.close();
            } catch (IOException e) {
                // TODO Back off mechanism
                // TODO Buffer metrics
                e.printStackTrace();
            }
        }
    }
}
