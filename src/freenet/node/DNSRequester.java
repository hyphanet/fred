/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
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
    private long lastLogTime;

    DNSRequester(Node node) {
        this.node = node;
        myThread = new Thread(this, "DNSRequester thread for "+node.portNumber);
        myThread.setDaemon(true);
    }

    void start() {
    	Logger.normal(this, "Starting DNSRequester");
    	System.out.println("Starting DNSRequester");
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
        PeerNode[] nodes = node.peers.myPeers;
        long now = System.currentTimeMillis();
        if((now - lastLogTime) > 1000) {
        	if(Logger.shouldLog(Logger.MINOR, this))
        		Logger.minor(this, "Processing DNS Requests (log rate-limited)");
            lastLogTime = now;
        }
        for(int i=0;i<nodes.length;i++) {
            //Logger.minor(this, "Node: "+pn);
            if(!nodes[i].isConnected()) {
                // Not connected
                // Try new DNS lookup
            	//Logger.minor(this, "Doing lookup on "+pn);
                nodes[i].maybeUpdateHandshakeIPs(false);
            }
        }
        try {
            synchronized(this) {
                wait(10000);  // sleep 10s ...
            }
        } catch (InterruptedException e) {
            // Ignore, just wake up. Just sleeping to not busy wait anyway
        }
    }
}
