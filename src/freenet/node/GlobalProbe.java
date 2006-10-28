/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.support.Logger;

public class GlobalProbe implements Runnable {

	double lastLocation = 0.0;
	boolean doneSomething = false;
	final ProbeCallback cb;
	final Node node;
	int ctr;
	
	GlobalProbe(Node n) {
		this.node = n;
    	cb = new ProbeCallback() {
			public void onCompleted(String reason, double target, double best, double nearest, long id, short counter) {
				String msg = "Completed probe request: "+target+" -> "+best+"\r\nNearest actually hit "+nearest+", "+counter+" hops, id "+id+"\r\n";
				Logger.error(this, msg);
				synchronized(GlobalProbe.this) {
					doneSomething = true;
					lastLocation = best;
					GlobalProbe.this.notifyAll();
				}
			}
    	};
		
	}
	
	public void run() {
		synchronized(this) {
			lastLocation = 0.0;
			double prevLoc = lastLocation;
			while(true) {
				doneSomething = false;
	    		node.dispatcher.startProbe(lastLocation, cb);
	    		for(int i=0;i<20 && !doneSomething;i++) {
	    			try {
						wait(1000*10);
					} catch (InterruptedException e) {
						// Ignore
					}
	    			// All vars should be updated by resynchronizing here, right?
	    		}
	    		if(!doneSomething) {
	    			error("Stalled on "+lastLocation+" , waiting some more.");
	    			try {
						wait(100*1000);
					} catch (InterruptedException e) {
						// Ignore
					}
	    			if(!doneSomething) {
	    				error("Still no response to probe request, trying again.");
		    			continue;
	    			}
	    		}
	    		if(Math.abs(lastLocation-prevLoc) < (Double.MIN_VALUE*2)) {
	    			error("Location is same as previous ! Sleeping then trying again");
	    			try {
						wait(100*1000);
					} catch (InterruptedException e) {
						// Ignore
					}
	    			continue;
	    		}
	    		output(lastLocation);
	    		prevLoc = lastLocation;
	    		if(lastLocation > 1.0 || Math.abs(lastLocation - 1.0) < 2*Double.MIN_VALUE) break;
	    		ctr++;
	    		// Sleep 10 seconds so we don't flood
	    		try {
					wait(10*1000);
				} catch (InterruptedException e) {
					// Ignore
				}
			}
		}
		
	}

	private void output(double loc) {
		double estimatedNodes = ((double) (ctr+1)) / loc;
		Logger.error(this, "LOCATION "+ctr+": " + loc+" - estimated nodes: "+estimatedNodes);
		System.out.println("LOCATION "+ctr+": " + loc+" - estimated nodes: "+estimatedNodes);
	}

	private void error(String string) {
		Logger.error(this, string);
		System.out.println("GlobalProbe error: "+string);
	}
	

}
