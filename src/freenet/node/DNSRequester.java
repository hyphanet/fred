/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.Logger.LogLevel;

/**
 * @author amphibian
 * 
 * Thread that does DNS queries for unconnected peers
 */
public class DNSRequester implements Runnable {

    final Node node;
    private long lastLogTime;
    // Only set when doing simulations.
    static boolean DISABLE = false;


    private static volatile boolean logMINOR;
    static {
        Logger.registerLogThresholdCallback(new LogThresholdCallback() {

            @Override
            public void shouldUpdate() {
                logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
            }
        });
    }

    DNSRequester(Node node) {
        this.node = node;
    }

    void start() {
    	Logger.normal(this, "Starting DNSRequester");
    	System.out.println("Starting DNSRequester");
    	node.executor.execute(this, "DNSRequester thread for "+node.getDarknetPortNumber());
    }

    @Override
    public void run() {
	    freenet.support.Logger.OSThread.logPID(this);
        while(true) {
            try {
                realRun();
            } catch (OutOfMemoryError e) {
				OOMHandler.handleOOM(e);
				System.err.println("Will retry above failed operation...");
            } catch (Throwable t) {
                Logger.error(this, "Caught in DNSRequester: "+t, t);
            }
        }
    }

    private void realRun() {
        PeerNode[] nodes = node.peers.myPeers();
        long now = System.currentTimeMillis();
        if((now - lastLogTime) > 1000) {
        	if(logMINOR)
        		Logger.minor(this, "Processing DNS Requests (log rate-limited)");
            lastLogTime = now;
        }
        for(PeerNode pn: nodes) {
            //Logger.minor(this, "Node: "+pn);
            if(!pn.isConnected()) {
                // Not connected
                // Try new DNS lookup
            	//Logger.minor(this, "Doing lookup on "+pn+" of "+nodes.length);
                pn.maybeUpdateHandshakeIPs(false);
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

	public void forceRun() {
		synchronized(this) {
			notifyAll();
		}
	}
}
