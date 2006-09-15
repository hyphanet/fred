/*
  TransferSendTest.java / Freenet
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

import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

import freenet.io.comm.Dispatcher;
import freenet.io.comm.Message;
import freenet.io.comm.Peer;
import freenet.io.comm.RetrievalException;
import freenet.io.comm.UdpSocketManager;
import freenet.io.comm.DMT;
import freenet.io.comm.MessageFilter;
import freenet.io.xfer.BlockReceiver;
import freenet.io.xfer.BlockTransmitter;
import freenet.io.xfer.PartiallyReceivedBlock;
import freenet.support.HexUtil;

/**
 * Transfer of random data with a SHA-1 checksum.
 * Takes two parameters: ourPort and hisPort.
 * Sends a ping message every second until we get 10, for handshaking.
 * Then sends a random block.
 * Also receives and handles.
 * SHA-1 is printed on both ends for verification.
 * @author amphibian
 */
public class TransferSendTest {



    /**
     *
     * TODO To change the template for this generated type comment go to
     * Window - Preferences - Java - Code Generation - Code and Comments
     */
    public static class Receiver implements Runnable {

        int uid;
        
        public Receiver(int uid) {
            this.uid = uid;
        }

        public void run() {
            // First send the ack
            usm.send(otherSide, DMT.createTestTransferSendAck(uid));
            // Receive the data
            PartiallyReceivedBlock prb;
            prb = new PartiallyReceivedBlock(32, 1024);
            BlockReceiver br;
            br = new BlockReceiver(usm, otherSide, uid, prb);
            byte[] block;
            try {
                block = br.receive();
            } catch (RetrievalException e1) {
                System.err.println("Failed to receive: "+e1);
                e1.printStackTrace();
                return;
            }
            System.err.println("Received "+block.length+" bytes");
            MessageDigest md;
            try {
                md = MessageDigest.getInstance("SHA-1");
            } catch (NoSuchAlgorithmException e) {
                throw new Error(e);
            }
            byte[] digest = md.digest(block);
            System.err.println("Block hash: "+HexUtil.bytesToHex(digest));
        }
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
            if(m.getSpec() == DMT.testTransferSend) {
                int uid = m.getInt(DMT.UID);
                System.err.println("Got send request, uid: "+uid);
                // Receive the actual data
                Receiver r = new Receiver(uid);
                new Thread(r).start();
                return true;
            }
            return false;
        }
    }
    
    static UdpSocketManager usm;
    static Peer otherSide;
    
    public TransferSendTest() {
        // not much to initialize
        super();
    }

    /**
     * 10 consecutive pings for handshaking.
     * Then create a random 32kB chunk of data.
     * Then send it.
     */
    public static void main(String[] args) throws SocketException, UnknownHostException, NoSuchAlgorithmException {
        if(args.length < 2) {
            System.err.println("Syntax: PingTest <myPort> <hisPort>");
            System.exit(1);
        }
        int myPort = Integer.parseInt(args[0]);
        int hisPort = Integer.parseInt(args[1]);
        System.out.println("My port: "+myPort+", his port: "+hisPort);
        // Set up a UdpSocketManager
        usm = new UdpSocketManager(myPort);
        usm.setDispatcher(new PingingReceivingDispatcher());
        otherSide = new Peer(InetAddress.getByName("127.0.0.1"), hisPort);
        int consecutivePings = 0;
        while(consecutivePings < 10) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
            }
            System.err.println("Sending ping");
            usm.send(otherSide, DMT.createPing());
            Message m = usm.waitFor(MessageFilter.create().setTimeout(1000).setType(DMT.pong).setSource(otherSide));
            if(m != null) {
                consecutivePings++;
                System.err.println("Got pong: "+m);
            } else consecutivePings = 0;
        }
        System.err.println("Got "+consecutivePings+" consecutive pings");
        byte[] buf = new byte[32768];
        Random r = new Random();
        r.nextBytes(buf);
        MessageDigest md;
        md = MessageDigest.getInstance("SHA-1");
        byte[] digest = md.digest(buf);
        String readableHash = HexUtil.bytesToHex(digest);
        System.err.println("Created block, size "+buf.length+", hash: "+readableHash);
        // Send transfer start message
        int uid = r.nextInt();
        System.err.println("UID: "+uid);
        Message start = DMT.createTestTransferSend(uid);
        usm.send(otherSide, start);
        // Wait for the ack
        Message m = usm.waitFor(MessageFilter.create().setType(DMT.testTransferSendAck));
        if(m == null) return;
        System.err.println("Got "+m);
        // Now send it
        PartiallyReceivedBlock prb = new PartiallyReceivedBlock(32, 1024, buf);
        BlockTransmitter bt;
        bt = new BlockTransmitter(usm, otherSide, uid, prb);
        bt.send();
    }
}
