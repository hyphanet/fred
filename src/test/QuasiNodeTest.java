/*
  QuasiNodeTest.java / Freenet
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

package test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
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
 * Requests are still 1-hop-only.
 */
public class QuasiNodeTest {


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

        public PeerNode route() {
            PeerNode[] nodes = getConnectedNodes();
            return nodes[r.nextInt(nodes.length)];
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
        usm.setDropProbability(10);
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
                    block = fetch(chk.getNodeCHK());
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
    private static void parseParameters(String[] args) {
        if(args.length < 2) {
            System.err.println("Syntax: QuasiNodeTest <myPort> <node1's port> <node2's port> <node3's port>... ");
            System.exit(1);
        }
        myPort = Integer.parseInt(args[0]);
        for(int i=1;i<args.length;i++) {
            int port = Integer.parseInt(args[i]);
            Logger.minor(QuasiNodeTest.class, "Adding node on port "+port);
            PeerNode p = new PeerNode(port);
            rt.addPeerNode(p);
        }
        Logger.normal(QuasiNodeTest.class, "Added "+rt.totalPeers()+" peers");
    }

    /**
     * Either fetch the key from the datastore, or request it 
     * from the other node.
     * @param nodeCHK The key to fetch.
     * @return null if we can't find the data.
     */
    private static CHKBlock fetch(NodeCHK nodeCHK) throws IOException, CHKVerifyException {
        // First try the store
        CHKBlock block = fs.fetch(nodeCHK);
        if(block != null) return block;
        // Otherwise...
        PeerNode pn = rt.route();
        Logger.minor(QuasiNodeTest.class, "Routing to "+pn);
        Peer peer = pn.peer;
        long id = r.nextLong();
        Message request = DMT.createTestRequest(nodeCHK, id, -1);
        usm.send(peer, request);
        // Wait for response
        Message reply = usm.waitFor(MessageFilter.create().setTimeout(5000).setType(DMT.testDataReply).setField(DMT.UID, id).setSource(peer).
                or(MessageFilter.create().setField(DMT.UID, id).setType(DMT.testDataNotFound).setSource(peer)));
        // Process reply
        if(reply == null) {
            Logger.normal(QuasiNodeTest.class, "Partner node did not reply");
            return null;
        } else if(reply.getSpec() == DMT.testDataNotFound) {
            // DNF
            Logger.normal(QuasiNodeTest.class, "Data Not Found");
            Message m = DMT.createTestDataNotFoundAck(id);
            usm.send(peer, m);
            // If this gets lost, they'll send it again a few times...
            return null;
        } else if(reply.getSpec() == DMT.testDataReply) {
            byte[] header = ((Buffer)reply.getObject(DMT.TEST_CHK_HEADERS)).getData();
            // Send the ack
            Message m = DMT.createTestDataReplyAck(id);
            usm.send(peer, m);
            // Now wait for the transfer; he will send me the data
            // Receive the data
            PartiallyReceivedBlock prb;
            prb = new PartiallyReceivedBlock(32, 1024);
            BlockReceiver br;
            br = new BlockReceiver(usm, peer, id, prb);
            byte[] data = null;
            for(int i=0;i<5;i++) {
                try {
                    data = br.receive();
                    break;
                } catch (RetrievalException e1) {
                    if(e1.getReason() == RetrievalException.SENDER_DIED) continue;
                    Logger.error(QuasiNodeTest.class, "Failed to receive", e1);
                    return null;
                }
            }
            if(data == null)
                Logger.error(PreQuasiNodeTest.class, "Could not receive data");
            System.err.println("Received "+data.length+" bytes");
            // Now decode it
            try {
                block = new CHKBlock(data, header, nodeCHK);
            } catch (CHKVerifyException e) {
                Logger.error(QuasiNodeTest.class, "Couldn't verify", e);
                return null;
            }
            return block;
        } else {
            Logger.error(QuasiNodeTest.class, "Message "+reply+" - WTF?");
            return null;
        }
    }

    private static void printHeader() {
        // Write header
        System.out.println("QuasiNode tester");
        System.out.println("----------------");
        System.out.println();
        System.out.println("Enter one of the following commands:");
        System.out.println("GET:<Freenet key> - fetch a key");
        System.out.println("PUT:\n<text, until a . on a line by itself> - We will insert the document and return the key.");
        System.out.println("PUT:<text> - put a single line of text to a CHK and return the key.");
        System.out.println("QUIT - exit the program");
    }
    /**
     * @author root
     *
     * TODO To change the template for this generated type comment go to
     * Window - Preferences - Java - Code Generation - Code and Comments
     */
    public static class MyDispatcher implements Dispatcher {
        public boolean handleMessage(Message m) {
            if(m.getSpec() == DMT.ping) {
                usm.send(m.getSource(), DMT.createPong(m));
                return true;
            }
            if(m.getSpec() == DMT.testRequest) {
                // Handle it
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
        }

        public void run() {
            CHKBlock block = null;
            try {
                // First try the store
                block = fs.fetch(key);
            } catch (IOException e) {
                Logger.error(this, "IO error fetching: "+e,e);
            } catch (CHKVerifyException e) {
                Logger.error(this, "Couldn't verify data in store for "+key+": "+e,e);
            }
            if(block != null) {
                byte[] header = block.getHeader();
                // First send the header
                Message m = DMT.createTestDataReply(id, header);
                Message ack = null;
                for(int i=0;i<5;i++) {
                    usm.send(otherSide, m);
                    // Wait for the ack
                    ack = usm.waitFor(MessageFilter.create().setType(DMT.testDataReplyAck).setTimeout(1000).setField(DMT.UID, id));
                    if(ack == null) {
                        // They didn't receive it.
                        // Try again.
                        usm.send(otherSide, m);
                    } else break;
                }
                if(ack == null) {
                    // ack still null
                    Logger.error(this, "Other side not acknowledging DataReply");
                    return;
                }
                // Now send the actual data
                byte[] data = block.getData();
                PartiallyReceivedBlock prb = new PartiallyReceivedBlock(32, 1024, data);
                BlockTransmitter bt = new BlockTransmitter(usm, otherSide, id, prb);
                bt.send();
                // All done
                return;
            } else {
                // block == null
                Message m = DMT.createTestDataNotFound(id);
                for(int i=0;i<5;i++) {
                    usm.send(otherSide, m);
                    // Wait for the ack
                    Message ack = usm.waitFor(MessageFilter.create().setType(DMT.testDataNotFoundAck).setField(DMT.UID, id).setTimeout(1000));
                    if(ack != null) return; // done :(
                    // Go around again;
                }
                // Still here, so they didn't ack
                Logger.error(this, "Other side not acknowledging DNF");
            }
        }
        
    }
}
