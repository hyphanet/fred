package freenet.node;

import java.util.LinkedList;

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
    final Thread myThread;
    final Node node;
    long lastClearedOldSwapChains;
    long lastReportedNoPackets;
    long lastReceivedPacketFromAnyNode;
    
    PacketSender(Node node) {
        resendPackets = new LinkedList();
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
                if(messages != null) {
                    // Send packets, right now, blocking, including any active notifications
                    node.packetMangler.processOutgoingOrRequeue(messages, pn, true);
                    continue;
                }
                
                // Need to send a keepalive packet?
                if(now - pn.lastSentPacketTime() > Node.KEEPALIVE_INTERVAL) {
                    Logger.minor(this, "Sending keepalive");
                    try {
						node.packetMangler.processOutgoing(null, 0, 0, pn);
					} catch (PacketSequenceException e) {
                    	Logger.error(this, "Caught "+e+" - disconnecting", e);
                    	pn.forceDisconnect();
					} catch (WouldBlockException e) {
						Logger.error(this, "Impossible: "+e, e);
					} catch (NotConnectedException e) {
						// Ignore: no point sending a keepalive now! :)
					}
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
}
