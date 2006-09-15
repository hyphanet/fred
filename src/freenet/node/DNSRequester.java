/*
  DNSRequester.java / Freenet
  Copyright (C) amphibian
  Copyright (C) 2005-2006 The Free Network project

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

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
