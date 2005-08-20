package freenet.node;

import java.util.LinkedList;

import freenet.support.Logger;

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
            try {
                ResendPacketItem item = null;
                do {
                    synchronized(this) {
                        if(!resendPackets.isEmpty())
                            item = (ResendPacketItem) resendPackets.removeFirst();
                        else break;
                    }
                    if(item.pn.isConnected())
                        node.packetMangler.processOutgoingPreformatted(item.buf, 0, item.buf.length, item.pn, item.packetNumber, item.callbacks);
                } while(item != null);
                long now = System.currentTimeMillis();
                PeerManager pm = node.peers;
                PeerNode[] nodes = pm.myPeers;
                long nextActionTime = Long.MAX_VALUE;
                for(int i=0;i<nodes.length;i++) {
                    PeerNode pn = nodes[i];
                    if(pn.isConnected()) {
                        // Is the node dead?
                        if(now - pn.lastReceivedPacketTime() > pn.maxTimeBetweenReceivedPackets()) {
                            pn.disconnected();
                            continue;
                        }

                        // Any messages to send?
                        MessageItem[] messages = null;
                        messages = pn.grabQueuedMessageItems();
                        if(messages != null) {
                            // Send packets, right now, blocking, including any active notifications
                            node.packetMangler.processOutgoingOrRequeue(messages, pn, true);
                            continue;
                        }
                        // Any urgent notifications to send?
                        long urgentTime = pn.getNextUrgentTime();
                        if(urgentTime <= now) {
                            // Send them
                            pn.sendAnyUrgentNotifications();
                        } else {
                            nextActionTime = Math.min(nextActionTime, urgentTime);
                        }
                        
                        // Need to send a keepalive packet?
                        if(now - pn.lastSentPacketTime() > Node.KEEPALIVE_INTERVAL) {
                            Logger.minor(this, "Sending keepalive");
                            node.packetMangler.processOutgoing(null, 0, 0, pn);
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
            } catch (Throwable t) {
                Logger.error(this, "Caught in PacketSender: "+t, t);
            }
        }
    }

    void queueResendPacket(byte[] payload, int packetNumber, KeyTracker k, AsyncMessageCallback[] callbacks) {
        Logger.minor(this, "Queueing resend packet: "+packetNumber+" for "+k+" with "+(callbacks == null ? 0 : callbacks.length)+" callbacks");
        ResendPacketItem item = new ResendPacketItem(payload, packetNumber, k, callbacks);
        synchronized(this) {
            resendPackets.add(item);
            notifyAll();
        }
    }
    
    class ResendPacketItem {
        public ResendPacketItem(byte[] payload, int packetNumber, KeyTracker k, AsyncMessageCallback[] callbacks) {
            pn = k.pn;
            kt = k;
            buf = payload;
            this.packetNumber = packetNumber;
            this.callbacks = callbacks;
        }
        final PeerNode pn;
        final KeyTracker kt;
        final byte[] buf;
        final int packetNumber;
        final AsyncMessageCallback[] callbacks;        
    }
}
