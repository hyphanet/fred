package test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.security.NoSuchAlgorithmException;
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
import freenet.keys.CHKDecodeException;
import freenet.keys.CHKEncodeException;
import freenet.keys.CHKVerifyException;
import freenet.keys.ClientCHK;
import freenet.keys.CHKBlock;
import freenet.keys.ClientCHKBlock;
import freenet.keys.FreenetURI;
import freenet.support.Buffer;
import freenet.support.Logger;

/**
 * Read from stdin, encode to a new style CHK, send to other node.
 * When receive it, decode and display.
 * Hence this is the world's slowest UDP chat client.
 * Takes two parameters: ourPort and hisPort.
 * Does a ping handshake first, like TransferSendTest does.
 * @author amphibian
 */
public class TransferBlockTest {



    /**
     * @author root
     *
     * TODO To change the template for this generated type comment go to
     * Window - Preferences - Java - Code Generation - Code and Comments
     */
    public static class Receiver implements Runnable {

        int uid;
        String uriS;
        FreenetURI uri;
        byte[] header;
        
        public Receiver(int uid, String uriS, byte[] header) throws MalformedURLException {
            this.uid = uid;
            this.header = header;
            this.uriS = uriS;
            this.uri = new FreenetURI(uriS);
        }

        public void run() {
            // First send the ack
            usm.send(otherSide, DMT.createTestSendCHKAck(uid, uriS));
            // Receive the data
            PartiallyReceivedBlock prb;
            prb = new PartiallyReceivedBlock(32, 1024);
            BlockReceiver br;
            br = new BlockReceiver(usm, otherSide, uid, prb);
            byte[] data;
            try {
                data = br.receive();
            } catch (RetrievalException e1) {
                Logger.error(this, "Failed to receive", e1);
                return;
            }
            System.err.println("Received "+data.length+" bytes");
            ClientCHK k;
            try {
                k = new ClientCHK(uri);
            } catch (MalformedURLException e3) {
                Logger.error(this, "Invalid URL sent by other side", e3);
                return;
            }
            // Now decode it
            CHKBlock block;
            try {
                block = new CHKBlock(data, header, k.getNodeCHK());
            } catch (CHKVerifyException e) {
                Logger.error(this, "Couldn't verify", e);
                return;
            }
            long tStart = System.currentTimeMillis();
            byte[] decoded;
            try {
                decoded = block.decode(k);
            } catch (CHKDecodeException e2) {
                Logger.error(this, "Couldn't decode sent data", e2);
                return;
            }
            long tEnd = System.currentTimeMillis();
            Logger.minor(this, "Time taken to decode: "+(tEnd-tStart)+"ms");
            Logger.minor(this, "Decoded: "+decoded.length+" bytes");
            Logger.normal(this, "Decoded data:\n"+new String(decoded));
            System.out.println("Decoded data:\n"+new String(decoded));
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
            if(m.getSpec() == DMT.testSendCHK) {
                int uid = m.getInt(DMT.UID);
                String uri = m.getString(DMT.FREENET_URI);
                Buffer buf = (Buffer)m.getObject(DMT.CHK_HEADER);
                byte[] header = buf.getData();
                Logger.minor(this, "Got send request, uid: "+uid+", key: "+uri);
                // Receive the actual data
                Receiver r;
                try {
                    r = new Receiver(uid, uri, header);
                } catch (MalformedURLException e) {
                    System.err.println(e.toString());
                    e.printStackTrace();
                    return true;
                }
                new Thread(r).start();
                return true;
            }
            return false;
        }
    }
    
    static UdpSocketManager usm;
    static Peer otherSide;
    
    public TransferBlockTest() {
        // not much to initialize
        super();
    }

    /**
     * 10 consecutive pings for handshaking.
     * Then repeatedly:
     * - Read a line from stdin.
     * - Encode it to a CHK
     * - Send it to the other node.
     */
    public static void main(String[] args) throws NoSuchAlgorithmException, CHKEncodeException, IOException {
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
        usm.setDispatcher(new PingingReceivingDispatcher());
        otherSide = new Peer(InetAddress.getByName("127.0.0.1"), hisPort);
        int consecutivePings = 0;
        while(consecutivePings < 5) {
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
        
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        
        Random r = new Random();
        
        while(true) {
            // Interface - goes to stdout, not log
            System.out.println();
            System.out.println("World's slowest UDP chat client");
            System.out.println("-------------------------------");
            System.out.println("Please enter message to send, terminate with . on a line by itself.");
            String message = "";
            while(true) {
                String read = reader.readLine();
                if(read.equals(".")) break;
                message += read;
                message += '\n';
            }
            Logger.debug(TransferBlockTest.class, "Read: "+message);
            // Encode to a CHK
            byte[] temp = message.getBytes();

            ClientCHKBlock block = ClientCHKBlock.encode(temp);
            ClientCHK chk = block.getClientKey();
            FreenetURI uri = 
                chk.getURI();
            // Interface, arguably
            System.out.println("URI: "+uri);
            byte[] header = block.getHeader();
            byte[] buf = block.getData();
            // Get a UID
            int uid = r.nextInt();
            Logger.minor(TransferBlockTest.class, "UID: "+uid);
            // Now send it to the other node
            Message sendKey = DMT.createTestSendCHK(uid, uri.toString(), new Buffer(header));
            usm.send(otherSide, sendKey);
            // Wait for ack
            Message m = usm.waitFor(MessageFilter.create().setType(DMT.testSendCHKAck));
            if(m == null) {
                // Interface?
                System.err.println("Did not receive ACK");
                return;
            }
            Logger.minor(TransferBlockTest.class, "Got ack: "+m);
            PartiallyReceivedBlock prb = new PartiallyReceivedBlock(32, 1024, buf);
            BlockTransmitter bt = new BlockTransmitter(usm, otherSide, uid, prb);
            bt.send();
            // Definitely interface.
            System.out.println("Sent.");
        }
    }
}
