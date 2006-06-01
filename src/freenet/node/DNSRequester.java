package freenet.node;

import freenet.support.Logger;

/**
 * @author amphibian
 * 
 * Thread that does DNS queries for unconnected peers
 */
public class DNSRequester implements Runnable {

    final Thread myThread;
    final Node node;
    private long lastLogTime = 0;
    private boolean firstCycle = true;

    DNSRequester(Node node) {
        this.node = node;
        myThread = new Thread(this, "DNSRequester thread for "+node.portNumber);
        myThread.setDaemon(true);
    }

    void start() {
			  Logger.normal(this,"Starting the DNSRequester thread");
        myThread.start();
    }

    public void run() {
        while(true) {
            try {
                realRun();
            } catch (OutOfMemoryError e) {
                Runtime r = Runtime.getRuntime();
                long usedAtStart = r.totalMemory() - r.freeMemory();
                System.gc();
                System.runFinalization();
                System.gc();
                System.runFinalization();
                System.err.println(e.getClass());
                System.err.println(e.getMessage());
                e.printStackTrace();
                long usedNow = r.totalMemory() - r.freeMemory();
                Logger.error(this, "Caught "+e, e);
                Logger.error(this, "Used: "+usedAtStart+" now "+usedNow);
            } catch (Throwable t) {
                Logger.error(this, "Caught in DNSRequester: "+t, t);
            }
        }
    }

    private void realRun() {
        PeerManager pm = node.peers;
        PeerNode[] nodes = pm.myPeers;
        long now = System.currentTimeMillis();
        // Run the time sensitive status updater separately
        for(int i=0;i<nodes.length;i++) {
            PeerNode pn = nodes[i];
            pn.setPeerNodeStatus(now);
        }
        if((now - lastLogTime) > 1000) {
            Logger.minor(this, "Processing DNS Requests (log rate-limited)");
            lastLogTime = now;
        }
        for(int i=0;i<nodes.length;i++) {
            PeerNode pn = nodes[i];
            if(!pn.isConnected()) {
                // Not connected
                // Try new DNS lookup
                pn.maybeUpdateHandshakeIPs(firstCycle);
            }
        }
        firstCycle = false;
        try {
            synchronized(this) {
                wait(200);  // sleep 200ms
            }
        } catch (InterruptedException e) {
            // Ignore, just wake up. Just sleeping to not busy wait anyway
        }
    }
}
