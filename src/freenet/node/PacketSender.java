package freenet.node;

import java.util.LinkedList;
import java.util.TreeMap;
import java.util.Vector;

import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.comm.NotConnectedException;
import freenet.support.Logger;
import freenet.support.WouldBlockException;

/**
 * @author amphibian
 * 
 * Thread that sends a packet whenever:
 * - A packet needs to be resent immediately
 * - Acknowledgements or resend requests need to be sent urgently.
 */
public class PacketSender implements Runnable {
    
    final LinkedList resendPackets;
    /** ~= Ticker :) */
    private final TreeMap timedJobsByTime;
    final Thread myThread;
    final Node node;
    long lastClearedOldSwapChains;
    long lastReportedNoPackets;
    long lastReceivedPacketFromAnyNode;
    
    PacketSender(Node node) {
        resendPackets = new LinkedList();
        timedJobsByTime = new TreeMap();
        this.node = node;
        myThread = new Thread(this, "PacketSender thread for "+node.portNumber);
        myThread.setDaemon(true);
    }

    void start() {
        myThread.start();
    }
    
    public void run() {
        while(true) {
            lastReceivedPacketFromAnyNode = lastReportedNoPackets;
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
                Logger.error(this, "Caught in PacketSender: "+t, t);
            }
        }
    }

    private void realRun() {
        long now = System.currentTimeMillis();
        PeerManager pm = node.peers;
        PeerNode[] nodes = pm.myPeers;
        long nextActionTime = Long.MAX_VALUE;
        for(int i=0;i<nodes.length;i++) {
            PeerNode pn = nodes[i];
            lastReceivedPacketFromAnyNode =
                Math.max(pn.lastReceivedPacketTime(), lastReceivedPacketFromAnyNode);
            if(pn.isConnected()) {
                // Is the node dead?
                if(now - pn.lastReceivedPacketTime() > pn.maxTimeBetweenReceivedPackets()) {
                	Logger.error(this, "Disconnecting from "+pn+" - haven't received packets recently");
                    pn.disconnected();
                    continue;
                }
                
                // Any urgent notifications to send?
                long urgentTime = pn.getNextUrgentTime();
                if(urgentTime <= now) {
                    // Send them
                    try {
						pn.sendAnyUrgentNotifications();
					} catch (PacketSequenceException e) {
                    	Logger.error(this, "Caught "+e+" - disconnecting", e);
                    	pn.forceDisconnect();
					}
                } else {
                    nextActionTime = Math.min(nextActionTime, urgentTime);
                }
                
                // Any packets to resend?
                for(int j=0;j<2;j++) {
                    KeyTracker kt;
                    if(j == 0) kt = pn.getCurrentKeyTracker();
                    else if(j == 1) kt = pn.getPreviousKeyTracker();
                    else break; // impossible
                    if(kt == null) continue;
                    ResendPacketItem[] resendItems = kt.grabResendPackets();
                    if(resendItems == null) continue;
                    for(int k=0;k<resendItems.length;k++) {
                        ResendPacketItem item = resendItems[k];
                        if(item == null) continue;
                        try {
                            Logger.minor(this, "Resending "+item.packetNumber+" to "+item.kt);
                            node.packetMangler.processOutgoingPreformatted(item.buf, 0, item.buf.length, item.kt, item.packetNumber, item.callbacks);
                        } catch (KeyChangedException e) {
                            Logger.error(this, "Caught "+e+" resending packets to "+kt);
                            pn.requeueResendItems(resendItems);
                            break;
                        } catch (NotConnectedException e) {
                            Logger.normal(this, "Caught "+e+" resending packets to "+kt);
                            pn.requeueResendItems(resendItems);
                            break;
                        } catch (PacketSequenceException e) {
                        	Logger.error(this, "Caught "+e+" - disconnecting", e);
                        	pn.forceDisconnect();
						} catch (WouldBlockException e) {
							Logger.error(this, "Impossible: "+e, e);
						}
                    }
                    
                }

                if(node.packetMangler == null) continue;
                // Any messages to send?
                MessageItem[] messages = null;
                messages = pn.grabQueuedMessageItems();
                if(messages != null && messages.length > 0) {
                	long l = Long.MAX_VALUE;
                	int sz = 56; // overhead; FIXME should be a constant or something
                	for(int j=0;j<messages.length;j++) {
                		if(l > messages[j].submitted) l = messages[j].submitted;
                		sz += 2 + /* FIXME only 2? */ messages[j].getData(node.packetMangler, pn).length;
                	}
                	if(l + 100 > now && sz < 1024) {
                		// Don't send immediately
                		if(nextActionTime > (l+100))
                			nextActionTime = l+100;
                		pn.requeueMessageItems(messages, 0, messages.length, true, "TrafficCoalescing");
                	} else {
                		for(int j=0;j<messages.length;j++) {
                			Logger.minor(this, "PS Sending: "+(messages[j].msg == null ? "(not a Message)" : messages[j].msg.getSpec().getName()));
                			if (messages[j].msg != null) {
                				pn.addToLocalNodeSentMessagesToStatistic(messages[j].msg);
                			}
                		}
                		// Send packets, right now, blocking, including any active notifications
                		node.packetMangler.processOutgoingOrRequeue(messages, pn, true, false);
                		continue;
                	}
                }
                
                // Need to send a keepalive packet?
                if(now - pn.lastSentPacketTime() > Node.KEEPALIVE_INTERVAL) {
                    Logger.minor(this, "Sending keepalive");
                   	// Force packet to have a sequence number.
                   	Message m = DMT.createFNPVoid();
                   	pn.addToLocalNodeSentMessagesToStatistic(m);
                   	node.packetMangler.processOutgoingOrRequeue(new MessageItem[] { new MessageItem(m, null) }, pn, true, true);
                }
            } else {
                // Not connected
                // Send handshake if necessary
                if(pn.shouldSendHandshake())
                    node.packetMangler.sendHandshake(pn);
            }
    	}
    	
        if(now - lastClearedOldSwapChains > 10000) {
            node.lm.clearOldSwapChains();
            lastClearedOldSwapChains = now;
        }
        
        // Send may have taken some time
        now = System.currentTimeMillis();
        
        Vector jobsToRun = null;
        
        synchronized(timedJobsByTime) {
        	while(!timedJobsByTime.isEmpty()) {
       			Long tRun = (Long) timedJobsByTime.firstKey();
       			if(tRun.longValue() <= now) {
       				if(jobsToRun == null) jobsToRun = new Vector();
       				Object o = timedJobsByTime.remove(tRun);
       				if(o instanceof Runnable[]) {
       					Runnable[] r = (Runnable[]) o;
       					for(int i=0;i<r.length;i++)
       						jobsToRun.add(r[i]);
       				} else {
       					Runnable r = (Runnable) o;
       					jobsToRun.add(r);
       				}
       			} else break;
        	}
        }

        if(jobsToRun != null)
        	for(int i=0;i<jobsToRun.size();i++) {
        		Runnable r = (Runnable) jobsToRun.get(i);
        		Logger.minor(this, "Running "+r);
        		if(r instanceof FastRunnable) {
        			// Run in-line
        			try {
        				r.run();
        			} catch (Throwable t) {
        				Logger.error(this, "Caught "+t+" running "+r, t);
        			}
        		} else {
        			Thread t = new Thread(r, "Scheduled job: "+r);
        			t.setDaemon(true);
        			t.start();
        		}
        	}
        
        long sleepTime = nextActionTime - now;
        // 200ms maximum sleep time
        sleepTime = Math.min(sleepTime, 200);
        
        if(now - node.startupTime > 60*1000*5) {
            if(now - lastReceivedPacketFromAnyNode > Node.ALARM_TIME) {
                Logger.error(this, "Have not received any packets from any node in last "+Node.ALARM_TIME/1000+" seconds");
                lastReportedNoPackets = now;
            }
        }
        
        if(sleepTime > 0) {
            try {
                synchronized(this) {
                    wait(sleepTime);
                }
            } catch (InterruptedException e) {
                // Ignore, just wake up. Probably we got interrupt()ed
                // because a new packet came in.
            }
        }
	}

	void queuedResendPacket() {
        // Wake up if needed
        synchronized(this) {
            notifyAll();
        }
    }

	public void queueTimedJob(Runnable job, long offset) {
		long now = System.currentTimeMillis();
		Long l = new Long(offset + now);
		synchronized(timedJobsByTime) {
			Object o = timedJobsByTime.get(l);
			if(o == null) {
				timedJobsByTime.put(l, job);
			} else if(o instanceof Runnable) {
				timedJobsByTime.put(l, new Runnable[] { (Runnable)o, job });
			} else if(o instanceof Runnable[]) {
				Runnable[] r = (Runnable[]) o;
				Runnable[] jobs = new Runnable[r.length+1];
				System.arraycopy(r, 0, jobs, 0, r.length);
				jobs[jobs.length-1] = job;
				timedJobsByTime.put(l, jobs);
			}
		}
	}
}
