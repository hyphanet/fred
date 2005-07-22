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
        myThread.start();
    }

    public void run() {
        while(true) {
            try {
            ResendPacketItem packet = null;
            synchronized(this) {
                if(!resendPackets.isEmpty()) {
                    packet = (ResendPacketItem)resendPackets.removeFirst();
                }
            }
            if(packet != null) {
                // Send the packet
                node.packetMangler.processOutgoingPreformatted(packet.buf, 0, packet.buf.length, packet.pn, packet.packetNumber);
            } else {
                long now = System.currentTimeMillis();
                long firstRemainingUrgentTime = Long.MAX_VALUE;
                // Anything urgently need sending?
                PeerManager pm = node.peers;
                if(pm == null) continue;
                for(int i=0;i<pm.connectedPeers.length;i++) {
                    NodePeer pn = node.peers.connectedPeers[i];
                    long urgentTime = pn.getNextUrgentTime();
                    if(urgentTime <= now) {
                        node.packetMangler.processOutgoing(null, 0, 0, pn);
                    } else {
                        if(firstRemainingUrgentTime > urgentTime)
                            urgentTime = firstRemainingUrgentTime;
                    }
                }
                if(lastClearedOldSwapChains - now > 10000) {
                    node.lm.clearOldSwapChains();
                    lastClearedOldSwapChains = now;
                }
                try {
                    Thread.sleep(Math.min(firstRemainingUrgentTime, 200));
                } catch (InterruptedException e) {
                    // Ignore, just wake up. Probably we got interrupt()ed
                    // because a new packet came in.
                }
            }
            } catch (Throwable t) {
                Logger.error(this, "Caught "+t, t);
            }
        }
    }

    class ResendPacketItem {
        public ResendPacketItem(byte[] payload, int packetNumber, NodePeer peer) {
            pn = peer;
            buf = payload;
            this.packetNumber = packetNumber;
        }
        final NodePeer pn;
        final byte[] buf;
        final int packetNumber;
    }
    
    /**
     * Queue a packet to send.
     * @param payload The message payload (one or more messages in the relevant format
     * including number of messages and respective lengths).
     * @param packetNumber If >0, then send the packet as this packetNumber.
     * @param peer The node to send it to.
     */
    public void queueForImmediateSend(byte[] payload, int packetNumber, NodePeer peer) {
        ResendPacketItem pi = new ResendPacketItem(payload, packetNumber, peer);
        synchronized(this) {
            resendPackets.addLast(pi);
            myThread.interrupt();
        }
    }

}