/*
  PreQuasiNodeTest.java / Freenet
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
 * First do 5 consecutive pings. Then:
 * Take requests and inserts from stdin text interface, like 
 * DatastoreTest.
 * Requests:
 * - If can answer from local datastore, do so.
 * - Otherwise route request to partner node.
 * - Partner node may return data, in which case use that.
 * Inserts:
 * - Insert goes just to this node.
 */
public class PreQuasiNodeTest {

    static FreenetStore fs;
    static UdpSocketManager usm;
    static Peer otherSide;
    final static Random r = new Random();
    
    public static void main(String[] args) throws Exception {
        if(args.length < 2) {
            System.err.println("Syntax: PingTest <myPort> <hisPort>");
            System.exit(1);
        }
        Logger.setupStdoutLogging(Logger.DEBUG, "");
        int myPort = Integer.parseInt(args[0]);
        int hisPort = Integer.parseInt(args[1]);
        Logger.minor(TransferBlockTest.class, "My port: "+myPort+", his port: "+hisPort);
        // Set up a UdpSocketManager
        usm = new UdpSocketManager(myPort);
        usm.setDropProbability(5);
        usm.setDispatcher(new PingingReceivingDispatcher());
        otherSide = new Peer(InetAddress.getByName("127.0.0.1"), hisPort);
        int consecutivePings = 0;
        while(consecutivePings < 3) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
            }
            Logger.normal(TransferBlockTest.class, "Sending ping");
            usm.send(otherSide, DMT.createPing());
            Message m = usm.waitFor(MessageFilter.create().setTimeout(1000).setType(DMT.pong).setSource(otherSide));
            if(m != null) {
                consecutivePings++;
                Logger.normal(TransferBlockTest.class, "Got pong: "+m);
            } else consecutivePings = 0;
        }
        Logger.normal(TransferBlockTest.class, "Got "+consecutivePings+" consecutive pings");
        
        // Setup datastore
        fs = new FreenetStore("datastore-"+myPort, "headerstore-"+myPort, 1024);
        // Setup logging
        Logger.setupStdoutLogging(Logger.DEBUG, "");
        printHeader();
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
        long id = r.nextLong();
        Message request = DMT.createTestRequest(nodeCHK, id, -1);
        usm.send(otherSide, request);
        // Wait for response
        Message reply = usm.waitFor(MessageFilter.create().setTimeout(5000).setType(DMT.testDataReply).setField(DMT.UID, id).
                or(MessageFilter.create().setField(DMT.UID, id).setType(DMT.testDataNotFound)));
        // Process reply
        if(reply == null) {
            Logger.normal(PreQuasiNodeTest.class, "Partner node did not reply");
            return null;
        } else if(reply.getSpec() == DMT.testDataNotFound) {
            // DNF
            Logger.normal(PreQuasiNodeTest.class, "Data Not Found");
            Message m = DMT.createTestDataNotFound(id);
            usm.send(otherSide, m);
            // If this gets lost, they'll send it again a few times...
            return null;
        } else if(reply.getSpec() == DMT.testDataReply) {
            byte[] header = ((Buffer)reply.getObject(DMT.TEST_CHK_HEADERS)).getData();
            // Send the ack
            Message m = DMT.createTestDataReplyAck(id);
            usm.send(otherSide, m);
            // Now wait for the transfer; he will send me the data
            // Receive the data
            PartiallyReceivedBlock prb;
            prb = new PartiallyReceivedBlock(32, 1024);
            BlockReceiver br;
            br = new BlockReceiver(usm, otherSide, id, prb);
            byte[] data = null;
            for(int i=0;i<5;i++) {
                try {
                    data = br.receive();
                    break;
                } catch (RetrievalException e1) {
                    if(e1.getReason() == RetrievalException.SENDER_DIED) continue;
                    Logger.error(PreQuasiNodeTest.class, "Failed to receive", e1);
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
                Logger.error(PreQuasiNodeTest.class, "Couldn't verify", e);
                return null;
            }
            return block;
        } else {
            Logger.error(PreQuasiNodeTest.class, "Message "+reply+" - WTF?");
            return null;
        }
    }

    private static void printHeader() {
        // Write header
        System.out.println("PreQuasiNode tester");
        System.out.println("-------------------");
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
    public static class PingingReceivingDispatcher implements Dispatcher {
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
