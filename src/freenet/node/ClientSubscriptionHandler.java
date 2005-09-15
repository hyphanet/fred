package freenet.node;

import java.util.Iterator;
import java.util.LinkedList;

import freenet.keys.ClientPublishStreamKey;
import freenet.support.Logger;

/**
 * Contains a list of ClientSubscriptionImpl's.
 * When they all disconnect, we unregister.
 */
public class ClientSubscriptionHandler {
    
    
    final ClientPublishStreamKey key;
    final SubscriptionManager sm;
    boolean finished = false;
    private final LinkedList clientSubs;
    
    ClientSubscriptionHandler(SubscriptionManager manager, ClientPublishStreamKey key) {
        clientSubs = new LinkedList();
        sm = manager;
        this.key = key;
    }
    
    synchronized void add(ClientSubscriptionImpl sub) {
        if(finished)
            throw new IllegalArgumentException();
        clientSubs.add(sub);
    }
    
    synchronized void remove(ClientSubscriptionImpl sub) {
        clientSubs.remove(sub);
        if(clientSubs.size() == 0) {
            finished = true;
            sm.remove(this);
        }
    }

    public ClientPublishStreamKey getKey() {
        return key;
    }

    /**
     * Received data!
     */
    public void processPacket(long packetNumber, byte[] packetData) {
        byte[] finalData = key.decrypt(packetNumber, packetData);
        if(finalData == null) {
            Logger.error(this, "Packet did not decrypt");
            return;
        }
        for(Iterator i=clientSubs.iterator();i.hasNext();) {
            ClientSubscriptionImpl impl = (ClientSubscriptionImpl)i.next();
            impl.processPacket(packetNumber, finalData);
        }
    }
}
