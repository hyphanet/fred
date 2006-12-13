package test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;

import freenet.io.comm.DMT;
import freenet.io.comm.Dispatcher;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.Peer;
import freenet.io.comm.RetrievalException;
import freenet.io.comm.UdpSocketManager;
import freenet.io.xfer.BlockReceiver;
import freenet.io.xfer.BlockTransmitter;
import freenet.io.xfer.PartiallyReceivedBlock;
import freenet.keys.CHKBlock;
import freenet.keys.CHKDecodeException;
import freenet.keys.CHKEncodeException;
import freenet.keys.CHKVerifyException;
import freenet.keys.ClientCHK;
import freenet.keys.ClientCHKBlock;
import freenet.keys.FreenetURI;
import freenet.keys.NodeCHK;
import freenet.store.FreenetStore;
import freenet.support.Buffer;
import freenet.support.Logger;

/**
 * Invoker provides list of nodes to connect to (port #s).
 * Do handshake with each.. 5 consecutive pings.
 * 
 * Then:
 * Take requests and inserts from stdin, like DatastoreTest.
 * Inserts:
 * - Just put to local store.
 * Requests:
 * - Choose a (random) peer to request from.
 *
 * Requests can now propagate. 
 */
public class PreNodeTest {

    /** SendJob: Send the DataReply, wait for the ack, send the
      * data, then report back. */
    public static class SendJob implements Runnable {

        final PartiallyReceivedBlock prb;
        final long id;
        final Peer peer;
        final byte[] header;
        final boolean silent;
        BlockTransmitter bt = null;
        boolean cancelled = false;
        
        public SendJob(PartiallyReceivedBlock prb, Peer peer, long id, byte[] header, boolean silent) {
            this.prb = prb;
            this.id = id;
            this.peer = peer;
            this.header = header;
            this.silent = silent;
        }

        public void run() {
            // First send the DataReply
            Message dataReply = DMT.createTestDataReply(id, header);
            // Yet another arbitrary timeout :(
            MessageFilter waitFor = MessageFilter.create().setSource(peer).setType(DMT.testDataReplyAck).setTimeout(1000);
            Message ack = null;
            for(int i=0;i<5;i++) {
                Logger.minor(this, "Waiting for DataReplyAck");
                usm.send(peer, dataReply);
                // Now wait for the ack
                ack = usm.waitFor(waitFor);
                if(ack != null)
                    break;
            }
            Message completionNotification = null;
            if(silent) completionNotification = null;
            if(ack == null) {
                if(!silent)
                    completionNotification =
                        DMT.createTestSendCompleted(id, false, "No DataReplyAck");
            } else {
                // Got an acknowledgement
                bt = new BlockTransmitter(usm, peer, id, prb);
                bt.send();
                if(!silent)
                    completionNotification = 
                        DMT.createTestSendCompleted(id, true, "");
            }
            if(!silent) usm.checkFilters(completionNotification);
        }
    }
    
    // ReceiveJob: Receive the entire file, then report back.
    public static class ReceiveJob implements Runnable {

        final PartiallyReceivedBlock prb;
        final Peer peer;
        final long id;
        
        public ReceiveJob(PartiallyReceivedBlock prb, Peer peer, long id) {
            this.prb = prb;
            this.peer = peer;
            this.id = id;
        }

        public void run() {
            BlockReceiver br = new BlockReceiver(usm, peer, id, prb);
            Message m = null;
            try {
                br.receive();
            } catch (RetrievalException e) {
                Logger.normal(this, "Receive failed: "+e);
                m = DMT.createTestReceiveCompleted(id, false, e.toString());
            }
            if(m == null)
                m = DMT.createTestReceiveCompleted(id, true, "");
            // Send notification
            usm.checkFilters(m);
        }

    }
    static Peer myPeer = null;

    /**
     * @author root
     *
     * TODO To change the template for this generated type comment go to
     * Window - Preferences - Java - Code Generation - Code and Comments
     */
    public static class IDSet {

        HashMap items = new HashMap();
        
        public synchronized void register(long id, Peer source) {
            items.put(new Long(id), source);
        }

        public void unregister(long id) {
            items.remove(new Long(id));
        }

        public Peer getSource(long id) {
            return (Peer) items.get(new Long(id));
        }
    }
    
    static final IDSet idsRunning = new IDSet();
    
    static final HashSet previousIDs = new HashSet();
    
    /**
     * @author amphibian
     * 
     * Keeps track of who we are connected to.
     * Also provides functionality for routing.
     * In a full implementation, we would keep estimators for
     * each node. In this version, we merely choose a peer 
     * randomly.
     */
    public static class RoutingTable {

        PeerNode[] peerNodes = null;

        HashSet connectedNodes = new HashSet();
        PeerNode[] connectedNodesArray;

        public int connectedNodes() {
            return connectedNodes.size();
        }

        public PeerNode route(HashSet peerNodesExcluded) {
            PeerNode[] nodes = getConnectedNodes();
            int length = nodes.length;
            if(peerNodesExcluded.size() > 0) {
                PeerNode[] nodesNotExcluded = new PeerNode[length];
                int j=0;
                for(int i=0;i<length;i++) {
                    PeerNode pn = nodes[i];
                    if(peerNodesExcluded.contains(pn)) {
                        Logger.minor(this, "Excluding: "+pn+" = "+pn.peer);
                        continue;
                    }
                    nodesNotExcluded[j] = pn;
                    j++;
                }
                nodes = nodesNotExcluded;
                length = j;
            }
            Logger.debug(this, "Route choices length: "+length);
            if(length == 0) return null;
            else return nodes[r.nextInt(length)];
        }
        
        public synchronized PeerNode[] getConnectedNodes() {
            if(connectedNodesArray == null) {
                connectedNodesArray = new PeerNode[connectedNodes.size()];
                connectedNodes.toArray(connectedNodesArray);
            }
            return connectedNodesArray;
        }

        /**
         * Try to connect to all nodes.
         */
        public synchronized void doInitialConnectAll() {
            // Connect to all peers
            if(peerNodes == null || peerNodes.length == 0) {
                Logger.error(this, "No peer nodes");
                return;
            }
            while(true) {
                boolean failedConnect = false;
                for(int i=0;i<peerNodes.length;i++) {
                    PeerNode pn = peerNodes[i];
                    Logger.debug(this, "["+i+"]: "+pn);
                    if(!pn.isConnected()) {
                        Logger.minor(this, "Trying to connect to "+pn);
                        if(!pn.tryConnectOnce())
                            failedConnect = true;
                    }
                }
                if(!failedConnect) break;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    // Ignore
                }
            }
        }

        public synchronized void addPeerNode(PeerNode p) {
            Logger.debug(this,"Adding "+p);
            if(peerNodes == null)
                Logger.debug(this, "peerNodes = null");
            else
                Logger.debug(this, "peerNodes size = "+peerNodes.length);
            int length;
            if(p == null) throw new NullPointerException();
            if(peerNodes == null) length = 0;
            else length = peerNodes.length;
            PeerNode[] newPeers = new PeerNode[length+1];
            if(length > 0)
                System.arraycopy(peerNodes, 0, newPeers, 0, peerNodes.length);
            newPeers[newPeers.length-1] = p;
            peerNodes = newPeers;
            for(int i=0;i<peerNodes.length;i++)
                Logger.debug(this, "peerNodes["+i+"] = "+peerNodes[i]);
        }

        public synchronized void onConnected(PeerNode p) {
            connectedNodes.add(p);
            connectedNodesArray = null;
        }
        
        public synchronized void onDisconnected(PeerNode p) {
            connectedNodesArray = null;
            // FIXME
        }

        /**
         * @return The total number of nodes known.
         */
        public int totalPeers() {
            return peerNodes.length;
        }

        /**
         * @param otherSide
         * @return
         */
        public PeerNode getPeerNode(Peer otherSide) {
            for(int i=0;i<peerNodes.length;i++) {
                PeerNode pn = peerNodes[i];
                if(pn.peer.equals(otherSide)) return pn;
            }
            return null;
        }
    }
    
    /**
     * A peer node. Would contain estimators as well as contact
     * details.
     * @author amphibian
     */
    public static class PeerNode {
        int portNumber;
        Peer peer;
        
        PeerNode(int portNum) {
            this.portNumber = portNum;
            try {
                peer = new Peer(InetAddress.getByName("127.0.0.1"), portNum);
            } catch (UnknownHostException e) {
                // WTF?
                throw new Error(e);
            }
        }

        public int hashCode() {
            return portNumber;
        }
        
        public String toString() {
            return super.toString()+":port="+portNumber;
        }

        public boolean equals(Object o) {
            // FIXME: do we need to actually compare content?
            // Probably not... there should only be one PeerNode for each peer.
            return (o == this);
        }
        
        /**
         * @return
         */
        public boolean isConnected() {
            return connected;
        }

        int consecutivePings = 0;
        boolean connected = false;
        
        /**
         * Attempt to connect to this node.
         * @return true if we have succeeded
         */
        public boolean tryConnectOnce() {
            if(!connected) {
                Logger.normal(TransferBlockTest.class, "Sending ping");
                usm.send(peer, DMT.createPing());
                Message m = usm.waitFor(MessageFilter.create().
                        setTimeout(1000).setType(DMT.pong).setSource(peer));
                if(m != null) {
                    consecutivePings++;
                    Logger.normal(TransferBlockTest.class, "Got pong: "+m);
                } else {
                    consecutivePings = 0;
                    connected = false;
                }
            }
            if(consecutivePings >= 3) {
                connected = true;
                rt.onConnected(this);
                return true;
            }
            Logger.normal(TransferBlockTest.class, "Got "+consecutivePings+" consecutive pings to "+this);
            return false;
        }
    }
    
    static FreenetStore fs;
    static UdpSocketManager usm;
    final static Random r = new Random();
    static RoutingTable rt;
    static int myPort;
    
    public static void main(String[] args) throws Exception {
        Logger.setupStdoutLogging(Logger.DEBUG, "");
        rt = new RoutingTable();
        // Parse parameters.
        parseParameters(args);
        // Set up a UdpSocketManager
        usm = new UdpSocketManager(myPort);
        usm.setDispatcher(new MyDispatcher());
        //usm.setDropProbability(10);
        rt.doInitialConnectAll();
        // Setup datastore
        fs = new FreenetStore("datastore-"+myPort, "headerstore-"+myPort, 1024);
        printHeader();
        interfaceLoop();
    }

    private static void interfaceLoop() throws IOException {
        // Read command, and data
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        while(true) {
            String line = reader.readLine();
            if(line.startsWith("GET:")) {
                // Should have a key next
                String key = line.substring("GET:".length());
                while(key.length() > 0 && key.charAt(0) == ' ')
                    key = key.substring(1);
                while(key.length() > 0 && key.charAt(key.length()-1) == ' ')
                    key = key.substring(0, key.length()-2);
                Logger.normal(DatastoreTest.class, "Key: "+key);
                FreenetURI uri = new FreenetURI(key);
                ClientCHK chk = new ClientCHK(uri);
                CHKBlock block;
                try {
                    // Fetch, possibly from other node.
                    block = runRequest(chk.getNodeCHK(), 3, r.nextLong(), null, null);
                } catch (CHKVerifyException e1) {
                    Logger.error(DatastoreTest.class, "Did not verify: "+e1, e1);
                    continue;
                }
                if(block == null) {
                    System.out.println("Not found in store: "+chk.getURI());
                } else {
                    // Decode it
                    byte[] decoded;
                    try {
                        decoded = block.decode(chk);
                    } catch (CHKDecodeException e) {
                        Logger.error(DatastoreTest.class, "Cannot decode: "+e, e);
                        continue;
                    }
                    System.out.println("Decoded data:\n");
                    System.out.println(new String(decoded));
                }
            } else if(line.startsWith("QUIT")) {
                System.out.println("Goodbye.");
                System.exit(0);
            } else if(line.startsWith("PUT:")) {
                // Just insert to local store
                line = line.substring("PUT:".length());
                while(line.length() > 0 && line.charAt(0) == ' ')
                    line = line.substring(1);
                while(line.length() > 0 && line.charAt(line.length()-1) == ' ')
                    line = line.substring(0, line.length()-2);
                String content;
                if(line.length() > 0) {
                    // Single line insert
                    content = line;
                } else {
                    // Multiple line insert
                    StringBuffer sb = new StringBuffer(1000);
                    while(true) {
                        line = reader.readLine();
                        if(line.equals(".")) break;
                        sb.append(line).append('\n');
                    }
                    content = sb.toString();
                }
                // Insert
                byte[] data = content.getBytes();
                ClientCHKBlock block;
                try {
                    block = ClientCHKBlock.encode(data);
                } catch (CHKEncodeException e) {
                    Logger.error(DatastoreTest.class, "Couldn't encode: "+e, e);
                    continue;
                }
                ClientCHK chk = block.getClientKey();
                FreenetURI uri = 
                    chk.getURI();
                fs.put(block);
                // Definitely interface
                System.out.println("URI: "+uri);
            } else {
                
            }
        }
    }

    /**
     * Parse parameters.
     * The first one is my port number.
     * The second, third, fourth... are the port numbers of
     * nodes to add to the routing table.
     */
    private static void parseParameters(String[] args) throws UnknownHostException {
        if(args.length < 2) {
            System.err.println("Syntax: QuasiNodeTest <myPort> <node1's port> <node2's port> <node3's port>... ");
            System.exit(1);
        }
        myPort = Integer.parseInt(args[0]);
        myPeer = new Peer(InetAddress.getByName("127.0.0.1"), myPort);
        for(int i=1;i<args.length;i++) {
            int port = Integer.parseInt(args[i]);
            Logger.minor(PreNodeTest.class, "Adding node on port "+port);
            PeerNode p = new PeerNode(port);
            rt.addPeerNode(p);
        }
        Logger.normal(PreNodeTest.class, "Added "+rt.totalPeers()+" peers");
    }

    /**
     * Either fetch the key from the datastore, or request it from the other
     * node.
     * 
     * @param nodeCHK
     *            The key to fetch.
     * @param htl
     *            The hops to live.
     * @return null if we can't find the data.
     */
    private static CHKBlock runRequest(NodeCHK nodeCHK, int htl, long id,
            Peer source, PeerNode sourceNode) throws IOException,
            CHKVerifyException {
        Logger.debug(PreNodeTest.class, "runRequest for "+nodeCHK+"@ HTL="+htl+" for "+source+" : "+sourceNode);
        previousIDs.add(new Long(id));
        idsRunning.register(id, source == null ? myPeer : source);
        // Send the ACK
        if (source != null) usm.send(source, DMT.createAcknowledgeRequest(id));
        try {
            HashSet peersSentTo = new HashSet();
            if (sourceNode != null) peersSentTo.add(sourceNode);
            // No source - catch any requests to this ID, check source later
            MessageFilter requestFilter = MessageFilter.create().setType(DMT.testRequest).setField(DMT.UID, id);
            while (true) {
                // First try the store
                CHKBlock block = fs.fetch(nodeCHK);
                if (block != null) {
                    if(source != null) {
                        // End node
                        PartiallyReceivedBlock prb = new PartiallyReceivedBlock(32, 1024, block.getData());
                        byte[] header = block.getHeader();
                        SendJob sj = new SendJob(prb, source, id, header, true);
                        Thread t = new Thread(sj);
                        t.start();
                    }
                    return block;
                }
                if (htl <= 0) return block;
                // Otherwise...
                PeerNode pn = rt.route(peersSentTo);
                if (pn == null) {
                    Logger.minor(PreNodeTest.class, "Ran out of peers");
                    // FIXME: should this be a separate message? LateQueryRejected, perhaps?
                    if(source != null) {
                        Message dnf = DMT.createTestDataNotFound(id);
                        usm.send(source, dnf);
                    }
                    return null;
                }
                Logger.minor(PreNodeTest.class, "Routing to " + pn);
                Peer peer = pn.peer;
                MessageFilter justReply = MessageFilter.create().setType(
                        DMT.testDataReply).setField(DMT.UID, id)
                        .setSource(peer);
                MessageFilter replyFilter = justReply.or(MessageFilter.create()
                        .setField(DMT.UID, id).setType(DMT.testDataNotFound)
                        .setSource(peer));
                Message request = DMT.createTestRequest(nodeCHK, id, htl);
                Message accepted = null;
                MessageFilter ackFilter = MessageFilter.create().setType(
                        DMT.acknowledgeRequest).setSource(peer).setField(
                        DMT.UID, id);
                MessageFilter qrFilter = MessageFilter.create().setType(
                        DMT.rejectDueToLoop).setSource(peer).setField(DMT.UID,
                        id);
                peersSentTo.add(pn);
                MessageFilter wait = qrFilter.or(ackFilter.or(replyFilter.or(requestFilter)));
                wait.setTimeout(1000);
                for (int i = 0; i < 5; i++) {
                    usm.send(peer, request);
                    // Wait for Accepted
                    accepted = usm.waitFor(wait);
                    if (accepted == null) continue;
                    if (accepted.getSpec() == DMT.testRequest) {
                        // Is a request
                        if(accepted.getSource() == source) {
                            // Is a rerequest.
                            // Resend the Accepted.
                            usm.send(source, DMT.createAcknowledgeRequest(id));
                        } else {
                            // Is a loop
                            usm.send(accepted.getSource(), DMT.createRejectDueToLoop(id));
                        }
                        accepted = null;
                        continue;
                    }
                    break;
                    // Didn't get Accepted - probably didn't receive our request
                }
                Logger.debug(PreNodeTest.class, "Waiting for Accepted got "+accepted);
                if (accepted == null) {
                    Logger.normal(PreNodeTest.class, "Did not get Accepted");
                    // Try another node
                    continue;
                }
                if (accepted.getSpec() == DMT.rejectDueToLoop) {
                    Logger.minor(PreNodeTest.class, "Rejected: Loop");
                    // Try another node
                    continue;
                }
                Message reply;
                // If it's not Accepted, it's something else...
                if (accepted.getSpec() != DMT.acknowledgeRequest)
                    reply = accepted;
                else {
                    while (true) {
                        Logger.debug(PreNodeTest.class, "Waiting for reply");
                        reply = usm.waitFor(replyFilter.or(requestFilter)
                                .setTimeout(5000));
                        if (reply == null) break;
                        if (reply.getSpec() == DMT.testRequest) {
                            if(reply.getSource() == source) {
                                // Rerequest from requestor; resend
                                usm.send(source, DMT.createAcknowledgeRequest(id));
                            } else {
                                // Loop
                                usm.send(reply.getSource(), DMT.createRejectDueToLoop(id));                                
                            }
                            continue;
                        }
                        break;
                    }
                }
                Logger.debug(PreNodeTest.class, "Got reply: "+reply);
                // Process reply
                if (reply == null) {
                    Logger.normal(PreNodeTest.class,
                            "Partner node did not reply");
                    // Same as if can't find enough nodes
                    continue;
                    //return null;
                } else if (reply.getSpec() == DMT.testDataNotFound) {
                    // DNF
                    Logger.normal(PreNodeTest.class, "Data Not Found");
                    Message m = DMT.createTestDataNotFoundAck(id);
                    usm.send(peer, m);
                    
                    if(source != null) {
                        Message dnf = DMT.createTestDataNotFound(id);
                        usm.send(source, dnf);
                    }
                    // If this gets lost, they'll send it again a few times...
                    return null;
                } else if (reply.getSpec() == DMT.testDataReply) {
                    Logger.debug(PreNodeTest.class, "Got DataReply");
                    byte[] header = ((Buffer) reply
                            .getObject(DMT.TEST_CHK_HEADERS)).getData();
                    // Send the ack
                    Message m = DMT.createTestDataReplyAck(id);
                    usm.send(peer, m);
                    // Now wait for the transfer; he will send me the data
                    // Receive the data
                    PartiallyReceivedBlock prb;
                    prb = new PartiallyReceivedBlock(32, 1024);
                    BlockReceiver br;

                    // Receive
                    // ReceiveJob: Receive the entire file, then report back.
                    ReceiveJob rj = new ReceiveJob(prb, peer, id);
                    Thread rjt = new Thread(rj);
                    rjt.start();

                    if(source != null) {
                        // Send
                        // SendJob: Send the DataReply, wait for the ack, send the
                        // data, then report back.
                        SendJob sj = new SendJob(prb, source, id, header, false);
                        Thread sjt = new Thread(sj);
                        sjt.start();
                    }

                    /**
                     * We can now receive: - Resent DataRequest from request
                     * source => resend - Resent DataReply from peer (data
                     * source) - Completion notification from SendJob -
                     * Completion notification from ReceiveJob SendJob handles
                     * DataReplyAck
                     */

                    MessageFilter sentCompleteWait = MessageFilter.create()
                            .setType(DMT.testSendCompleted).setField(DMT.UID,
                                    id);
                    MessageFilter receiveCompleteWait = MessageFilter.create()
                            .setType(DMT.testReceiveCompleted).setField(
                                    DMT.UID, id);

                    // FIXME: totally arbitrary timeout :)
                    MessageFilter waitingFor = 
                        justReply.or(sentCompleteWait.or(receiveCompleteWait.or(requestFilter)));
                    waitingFor = waitingFor.setTimeout(30 * 1000);

                    boolean sendCompleted = false;
                    boolean recvCompleted = false;

                    while (true) {
                        m = null;
                        m = usm.waitFor(waitingFor);

                        if (m == null) {
                            // Timeout
                            Logger.error(PreNodeTest.class, "Timeout in final wait");
                            if(!recvCompleted) {
                                // Other side will see broken send
                                return null;
                            } else {
                                // We got it, they didn't
                                break;
                            }
                        } else if (m.getSpec() == DMT.testSendCompleted) {
                            Logger.minor(PreNodeTest.class, "Send completed");
                            // Finished send
                            sendCompleted = true;
                            if (recvCompleted) break;
                        } else if (m.getSpec() == DMT.testReceiveCompleted) {
                            Logger.minor(PreNodeTest.class, "Receive completed");
                            if(!m.getBoolean(DMT.SUCCESS)) {
                                prb.abort(RetrievalException.SENDER_DIED, "Sender died");
                                return null;
                            }
                            recvCompleted = true;
                            if (sendCompleted || source == null) break;
                        } else if (m.getSpec() == DMT.testDataReply) {
                            Logger.minor(PreNodeTest.class, "Got DataReply");
                            // Data source didn't get the acknowledgement
                            // Resend the acknowledgement
                            Message ack = DMT.createTestDataReplyAck(id);
                            usm.send(peer, ack);
                        } else if (m.getSpec() == DMT.testRequest) {
                            Logger.minor(PreNodeTest.class, "Got DataRequest");
                            if(m.getSource() == source) {
                                Logger.minor(PreNodeTest.class, "DataRequest from source");
                                // Resend request
                                // Source got neither the accepted nor the DataReply
                                // Resend the accepted, and let SendJob resend the DataReply
                                // Difficult to get it to resend it immediately because it might just have sent it so just let it time out.
                                usm.send(source, DMT.createAcknowledgeRequest(id));
                            } else {
                                // Loop - shouldn't happen at this stage
                                Logger.normal(PreNodeTest.class, "Loop after have started sending");
                                usm.send(m.getSource(), DMT.createRejectDueToLoop(id));                                
                            }
                            
                        }
                    }

                    // Got data

                    byte[] data = prb.getBlock();

                    if (data == null)
                            Logger.error(PreQuasiNodeTest.class,
                                    "Could not receive data");
                    System.err.println("Received " + data.length + " bytes");
                    // Now decode it
                    try {
                        block = new CHKBlock(data, header, nodeCHK);
                    } catch (CHKVerifyException e) {
                        Logger.error(PreNodeTest.class, "Couldn't verify", e);
                        return null;
                    }
                    return block;
                } else {
                    Logger.error(PreNodeTest.class, "Message " + reply
                            + " - WTF?");
                    return null;
                }
            }
        } finally {
            idsRunning.unregister(id);
        }
    }

    private static void printHeader() {
        // Write header
        System.out.println("PreNode tester");
        System.out.println("--------------");
        System.out.println();
        System.out.println("Enter one of the following commands:");
        System.out.println("GET:<Freenet key> - fetch a key");
        System.out.println("PUT:\n<text, until a . on a line by itself> - We will insert the document and return the key.");
        System.out.println("PUT:<text> - put a single line of text to a CHK and return the key.");
        System.out.println("QUIT - exit the program");
    }

    public static class MyDispatcher implements Dispatcher {
        public boolean handleMessage(Message m) {
            if(m.getSpec() == DMT.ping) {
                usm.send(m.getSource(), DMT.createPong(m));
                return true;
            }
            if(m.getSpec() == DMT.testRequest) {
                Peer origSource = idsRunning.getSource(m.getLong(DMT.UID));
                Peer reqSource = m.getSource();
                if(reqSource == origSource)
                    // Resent by request source; ignore
                    return true;
                // Otherwise a genuine request, or a loop; either way needs further handling
                try {
                    new Thread(new RequestHandler(m)).start();
                } catch (IllegalStateException e) {
                    return true;
                }
                return true;
            }
            return false;
        }
    }
    
    /**
     * Handle a request.
     * Check the store, if we have anything, then send it back.
     * Otherwise send back DNF.
     */
    public static class RequestHandler implements Runnable {

        final long id;
        final NodeCHK key;
        final Peer otherSide;
        int htl;
        
        /**
         * Constructor
         * @param m
         */
        public RequestHandler(Message m) {
            if(m.getSpec() != DMT.testRequest)
                throw new IllegalArgumentException("Not a testRequest: "+m.getSpec().getName());
            id = m.getLong(DMT.UID);
            Object o = m.getObject(DMT.FREENET_ROUTING_KEY);
            if(o instanceof NodeCHK)
                key = (NodeCHK) o;
            else {
                // Ignore it
                Logger.error(RequestHandler.class, "Node sent testRequest but key not a key! Ignoring request.");
                throw new IllegalStateException("Node sent testRequest but key not a key! Ignoring request.");
            }
            otherSide = m.getSource();
            htl = m.getInt(DMT.HTL);
            htl--;
        }

        public void run() {
            // Check ID
            // FIXME: use an LRU
            Long lid = new Long(id);
            if(previousIDs.contains(lid)) {
                // Reject
                usm.send(otherSide, DMT.createRejectDueToLoop(id));
                return;
            }
            try {
                runRequest(key, htl, id, otherSide, rt.getPeerNode(otherSide));
            } catch (IOException e) {
                Logger.error(this, "IO error fetching: "+e,e);
            } catch (CHKVerifyException e) {
                Logger.error(this, "Couldn't verify data in store for "+key+": "+e,e);
            }
        }
        
    }
}
