/*
 * Freenet 0.7 node.
 * 
 * Designed primarily for darknet operation, but should also be usable
 * in open mode eventually.
 */
package freenet.node;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import freenet.crypt.RandomSource;
import freenet.crypt.Yarrow;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.UdpSocketManager;
import freenet.keys.ClientCHK;
import freenet.keys.ClientCHKBlock;
import freenet.store.BaseFreenetStore;
import freenet.store.FreenetStore;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

/**
 * @author amphibian
 */
public class Node implements SimpleClient {
    
    // FIXME: abstract out address stuff? Possibly to something like NodeReference?
    final int portNumber;
    final FreenetStore datastore;
    byte[] myIdentity; // FIXME: simple identity block; should be unique
    byte[] identityHash;
    final LocationManager lm;
    final PeerManager peers; // my peers
    final RandomSource random; // strong RNG
    final UdpSocketManager usm;
    final FNPPacketMangler packetMangler;
    final PacketSender ps;
    private static final int EXIT_STORE_FILE_NOT_FOUND = 1;
    private static final int EXIT_STORE_IOEXCEPTION = 2;
    private static final int EXIT_STORE_OTHER = 3;
    private static final int EXIT_USM_DIED = 4;
    public static final int EXIT_YARROW_INIT_FAILED = 5;

    /**
     * Read all storable settings (identity etc) from the node file.
     * @param filename The name of the file to read from.
     */
    private void readNodeFile(String filename) throws IOException {
    	// REDFLAG: Any way to share this code with NodePeer?
        FileInputStream fis = new FileInputStream(filename);
        InputStreamReader isr = new InputStreamReader(fis);
        BufferedReader br = new BufferedReader(isr);
        SimpleFieldSet fs = new SimpleFieldSet(br);
        br.close();
        // Read contents
        String physical = fs.get("physical.udp");
        Peer myOldPeer;
        try {
            myOldPeer = new Peer(physical);
        } catch (PeerParseException e) {
            IOException e1 = new IOException();
            e1.initCause(e);
            throw e1;
        }
        if(myOldPeer.getPort() != portNumber)
            throw new IllegalArgumentException("Wrong port number "+
                    myOldPeer.getPort()+" should be "+portNumber);
        // FIXME: we ignore the IP for now, and hardcode it to localhost
        String identity = fs.get("identity");
        myIdentity = HexUtil.hexToBytes(identity);
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new Error(e);
        }
        identityHash = md.digest(myIdentity);
	    String loc = fs.get("location");
	    Location l;
        try {
            l = new Location(loc);
        } catch (FSParseException e) {
            IOException e1 = new IOException();
            e1.initCause(e);
            throw e1;
        }
        lm.setLocation(l);
    }

    private void writeNodeFile(String filename, String backupFilename) throws IOException {
        SimpleFieldSet fs = exportFieldSet();
        File orig = new File(filename);
        File backup = new File(backupFilename);
        orig.renameTo(backup);
        FileOutputStream fos = new FileOutputStream(backup);
        OutputStreamWriter osr = new OutputStreamWriter(fos);
        fs.writeTo(osr);
        osr.close();
    }

    private void initNodeFileSettings(RandomSource r) {
        // Don't need to set portNumber
        // FIXME use a real IP!
    	myIdentity = new byte[32];
    	r.nextBytes(myIdentity);
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new Error(e);
        }
        identityHash = md.digest(myIdentity);
    	// Don't need to set location it's already randomized
    }

    /**
     * Read the port number from the arguments.
     * Then create a node.
     */
    public static void main(String[] args) {
        int port = Integer.parseInt(args[0]);
        System.out.println("Port number: "+port);
        Logger.setupStdoutLogging(Logger.MINOR, "");
        Node n = new Node(port);
        new TextModeClientInterface(n);
    }
    
    Node(int port) {
        portNumber = port;
        try {
            datastore = new BaseFreenetStore("freenet-"+portNumber,1024);
        } catch (FileNotFoundException e1) {
            Logger.error(this, "Could not open datastore: "+e1, e1);
            System.exit(EXIT_STORE_FILE_NOT_FOUND);
            throw new Error();
        } catch (IOException e1) {
            Logger.error(this, "Could not open datastore: "+e1, e1);
            System.exit(EXIT_STORE_IOEXCEPTION);
            throw new Error();
        } catch (Exception e1) {
            Logger.error(this, "Could not open datastore: "+e1, e1);
            System.exit(EXIT_STORE_OTHER);
            throw new Error();
        }
        random = new Yarrow();

		lm = new LocationManager(random);

        try {
        	readNodeFile("node-"+portNumber);
        } catch (IOException e) {
            try {
                readNodeFile("node-"+portNumber+".bak");
            } catch (IOException e1) {
                initNodeFileSettings(random);
            }
        }
        try {
            writeNodeFile("node-"+portNumber, "node-"+portNumber+".bak");
        } catch (IOException e) {
            Logger.error(this, "Cannot write node file!: "+e+" : "+"node-"+portNumber);
        }
        
        ps = new PacketSender(this);
        peers = new PeerManager(this, "peers");
        
        try {
            usm = new UdpSocketManager(portNumber);
            usm.setDispatcher(new NodeDispatcher());
            usm.setLowLevelFilter(packetMangler = new FNPPacketMangler(this));
        } catch (SocketException e2) {
            Logger.error(this, "Could not listen for traffic: "+e2, e2);
            System.exit(EXIT_USM_DIED);
            throw new Error();
        }
    }

    /* (non-Javadoc)
     * @see freenet.node.SimpleClient#getCHK(freenet.keys.ClientCHK)
     */
    public ClientCHKBlock getCHK(ClientCHK key) {
        // TODO Auto-generated method stub
        return null;
    }

    /* (non-Javadoc)
     * @see freenet.node.SimpleClient#putCHK(freenet.keys.ClientCHKBlock)
     */
    public void putCHK(ClientCHKBlock key) {
        // TODO Auto-generated method stub
        
    }

    /**
     * Export my reference so that another node can connect to me.
     * @return
     */
    public SimpleFieldSet exportFieldSet() {
        SimpleFieldSet fs = new SimpleFieldSet();
        InetAddress addr;
        try {
            addr = InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            Logger.error(this, "Caught "+e+" trying to get localhost!");
            return null;
        }
        fs.put("physical.udp", addr.getHostAddress()+":"+portNumber);
        fs.put("identity", HexUtil.bytesToHex(myIdentity));
        fs.put("location", Double.toString(lm.getLocation().getValue()));
        return fs;
    }
}
