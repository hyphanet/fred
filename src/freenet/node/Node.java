package freenet.node;

import java.io.File;
import java.io.IOException;
import java.net.SocketException;

import freenet.crypt.RandomSource;
import freenet.crypt.Yarrow;
import freenet.io.comm.DMT;
import freenet.io.comm.Dispatcher;
import freenet.io.comm.Message;
import freenet.io.comm.UdpSocketManager;
import freenet.keys.CHKBlock;
import freenet.keys.Key;
import freenet.keys.NodeCHK;
import freenet.node.rt.RandomRouterFactory;
import freenet.node.states.DataRequestClientHandler;
import freenet.node.states.DataRequestServerHandler;
import freenet.store.FreenetStore;
import freenet.support.Logger;
import freenet.support.config.Config;
import freenet.support.config.RandomPortOption;

/**
 * @author amphibian
 * 
 * A Freenet node.
 */
public class Node implements SimpleClientInterface, Dispatcher {

    final FredConfig fc;
    final UserAlertManager uam;
    UdpSocketManager usm = null;
    // FIXME: change to NodeReference?
    int myPort = -1;
    final PeerManager pm;
    final RandomSource rand;
    FreenetStore store;
    ChainManager chains;
    
    /**
     * Create Node.
     * @param fc The configuration object.
     * @param uam Thing to send UserAlert's to.
     */
    public Node(FredConfig fc, UserAlertManager uam) {
        this.fc = fc;
        this.uam = uam;
        this.rand = new Yarrow((new File("/dev/urandom").exists() ? "/dev/urandom" : "prng.seed"), "SHA1", "Rijndael",true);
        
        // Must create UdpSocketManager in the callback
        fc.register(this, config, "node");
        
        // FIXME: real routing!
        pm = new PeerManager(new RandomRouterFactory(rand));
        pm.setNode(this);
    }
    
    // Config definitions
    static Config config;
    
    static {
        // Add options
        config.addOption(new RandomPortOption("listenPort", 1, 101));
        // Detail for user
		// listenPort
		config.argDesc("listenPort", "<port no.>");
		config.shortDesc("listenPort", "incoming FNP port");
		config.longDesc("listenPort",
			"The port to listen for incoming FNP (Freenet Node Protocol) connections on.");
    }

    // Config callbacks
    
    public int getListenPort() {
        return myPort;
    }
    
    public void setListenPort(int port) {
        if(port == myPort) return;
        this.myPort = port;
        if(usm != null) {
            usm.close(true);
            usm = null;
            Logger.normal(this, "Closing UdpSocketManager because port changed to "+port);
        }
        
        try {
            usm = new UdpSocketManager(port);
            usm.setDispatcher(this);
            usm.setLowLevelFilter(pm);
        } catch (SocketException e) {
            // FIXME: could handle better?
            Logger.error(this, "Could not create socket manager on port "+port, e);
            // FIXME: Now what?
            // FIXME: schedule an alarm on the UAM, but stay running.
            // User should then create a new port, or restart the node.
        }
    }
    
    // Client interface methods
    
    public CHKBlock simpleGet(Key k) {
        // TODO Auto-generated method stub
        return null;
    }

    public boolean simplePut(CHKBlock chk) {
        // TODO Auto-generated method stub
        return false;
    }

    // Dispatcher interface
    
    /**
     * Dispatch a raw message. The message has already been decoded
     * because of the low-level-filter callback arrangement.
     * @return false if we can't do anything with the message and want 
     * it to stay on the queue.
     */
    public boolean handleMessage(Message m) {
        PeerNode pn = 
            (PeerNode) m.getObject(DMT.SOURCE_PEER);
        if(m.getSource() != null) {
            Logger.error(this, "Message source must be null - we cannot match on message source, it is insecure");
            return true;
        }
        if(m.getSpec() == DMT.FNPDataRequest) {
            // It could be a rerequest from the original requestor
            long id = m.getLong(DMT.UID);
            int htl = m.getInt(DMT.HTL);
            Chain c = chains.getChain(id);
            if(c == null && !chains.checkOldIDs(id)) {
                // New request - process
                // Firstly, do we want to accept the request?
                Object o = m.getObject(DMT.FREENET_ROUTING_KEY);
                if(!(o instanceof NodeCHK)) {
                    Logger.normal(this, "Received message but key not a NodeCHK: "+o.getClass());
                    return true; // corrupt
                }
                NodeCHK key = (NodeCHK) o;
                if(acceptRequest(key)) {
                    // First check datastore
                    CHKBlock block;
                    try {
                        block = store.fetch(key);
                    } catch (IOException e) {
                        Logger.error(this, "Error reading from store: "+e, e);
                        block = null;
                        // FIXME: should we tell the user via UAM? Probably...
                    }
                    if(block != null) {
                        // Just send it
                        DataRequestClientHandler ch = new DataRequestClientHandler(id, key, block);
                        new Thread(ch).start();
                        chains.addOldID(id);
                        return true;
                    }
                    // Try to coalesce
                    Chain peerChain = chains.getChain(key, htl);
                    if(peerChain != null) {
                        // Already being fetched
                        DataRequestClientHandler ch = new DataRequestClientHandler(id, key, peerChain.getServerHandler());
                        new Thread(ch).start();
                        chains.addOldID(id);
                        return true;
                    }
                    // Otherwise a completely new request
                    DataRequestServerHandler sh = new DataRequestServerHandler(id, htl, key);
                    DataRequestClientHandler ch = new DataRequestClientHandler(id, key, sh);
                    chains.add(id, key, sh, pn);
                    new Thread(ch).start();
                    new Thread(sh).start();
                    return true;
                } else {
                    // Reject the request due to overload.
                    // This is fatal; it propagates back to the original requestor,
                    // who is obliged to reduce his window size.
                    sendMessageAsync(pn, DMT.createFNPRejectOverload(id));
                    chains.addOldID(id);
                    return true;
                }
            } else {
                if(c.getSource().equals(pn)) {
                    // From requestor - process may want to know
                    // as this is a rerequest
                    return false;
                } else {
                    // From somebody else - loop
                    sendMessageAsync(pn, DMT.createFNPRejectLoop(id));
                    return true; // Dealt with it
                }
            }
        } else {
            return false; // No idea what to do with it
        }
    }

    /**
     * Send a message off-thread. We do not care whether it arrives.
     * @param pn The PeerNode to send the message to.
     * @param message The message to be sent.
     */
    private void sendMessageAsync(PeerNode pn, Message message) {
        // TODO: implement!
    }

    /**
     * @return True if we want to accept the request.
     */
    private boolean acceptRequest(NodeCHK key) {
        // FIXME: we want to reject some!
        return true;
    }

    /**
     * Do we want more incoming connections?
     */
    public boolean wantConnections() {
        // FIXME: we don't always! Implement!
        return true;
    }
}
