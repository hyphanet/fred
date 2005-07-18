package freenet.node;

import java.util.LinkedList;

/**
 * @author amphibian
 * 
 * Thread that sends a packet whenever:
 * - A packet needs to be resent immediately
 * - Acknowledgements or resend requests need to be sent urgently.
 */
public class PacketSender implements Runnable {
    
    LinkedList resendPackets;
    Thread myThread;
    Node node;
    
    PacketSender(Node node) {
        myThread = new Thread(this);
        myThread.start();
        myThread.setDaemon(true);
    }

    public void run() {
        while(true) {
            ResendPacketItem packet = null;
            synchronized(this) {
                if(!resendPackets.isEmpty()) {
                    packet = (ResendPacketItem)resendPackets.removeFirst();
                }
            }
            if(packet != null) {
                // Send the packet
                node.packetMangler.processOutgoing(packet.buf, 0, packet.buf.length, packet.pn);
            } else {
                long now = System.currentTimeMillis();
                long firstRemainingUrgentTime = Long.MAX_VALUE;
                // Anything urgently need sending?
                for(int i=0;i<node.peers.connectedPeers.length;i++) {
                    NodePeer pn = node.peers.connectedPeers[i];
                    long urgentTime = pn.nextUrgentTime;
                    if(urgentTime <= now) {
                        node.packetMangler.processOutgoing(null, 0, 0, pn);
                    } else {
                        if(firstRemainingUrgentTime > urgentTime)
                            urgentTime = firstRemainingUrgentTime;
                    }
                }
                try {
                    Thread.sleep(Math.min(firstRemainingUrgentTime, 200));
                } catch (InterruptedException e) {
                    // Ignore, just wake up. Probably we got interrupt()ed
                    // because a new packet came in.
                }
            }
        }
    }

    class ResendPacketItem {
        public ResendPacketItem(byte[] payload, NodePeer peer) {
            pn = peer;
            buf = payload;
        }
        final NodePeer pn;
        final byte[] buf;
    }
    
    /**
     * @param payload
     * @param peer
     */
    public void queueForImmediateSend(byte[] payload, NodePeer peer) {
        ResendPacketItem pi = new ResendPacketItem(payload, peer);
        synchronized(this) {
            resendPackets.addLast(pi);
            myThread.interrupt();
        }
    }

}