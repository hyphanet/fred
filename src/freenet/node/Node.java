/*
 * Freenet 0.7 node.
 * 
 * Designed primarily for darknet operation, but should also be usable
 * in open mode eventually.
 */
package freenet.node;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.SocketException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import freenet.crypt.RandomSource;
import freenet.crypt.Yarrow;
import freenet.io.comm.UdpSocketManager;
import freenet.keys.ClientCHK;
import freenet.keys.ClientCHKBlock;
import freenet.store.BaseFreenetStore;
import freenet.store.FreenetStore;
import freenet.support.Logger;

/**
 * @author amphibian
 */
public class Node implements SimpleClient {
    
    // FIXME: abstract out address stuff?
    final int portNumber;
    final FreenetStore datastore;
    final byte[] myIdentity; // FIXME: simple identity block; should be unique
    final byte[] identityHash;
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

    /**
     * Read the port number from the arguments.
     * Then create a node.
     */
    public static void main(String[] args) {
        int port = Integer.parseInt(args[0]);
        System.out.println("Port number: "+port);
        Node n = new Node(port);
        TextModeClientInterface tmci = new TextModeClientInterface(n);
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
        Location myLoc;
        try {
            myLoc = Location.read("location");
        } catch (IOException e) {
            myLoc = Location.randomInitialLocation(random);
            Logger.normal(this, "Creating random initial location: "+myLoc);
        }
        lm = new LocationManager(myLoc);
        writeLocation();
        ps = new PacketSender(this);
        peers = new PeerManager(this, "peers");
        // FIXME: HACK
        String s = "testnode-"+portNumber;
        myIdentity = s.getBytes();
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new Error(e);
        }
        identityHash = md.digest(myIdentity);
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

    /**
     * Write the Location to disk.
     */
    private void writeLocation() {
        Location l = lm.getLocation();
        try {
            l.write("location");
        } catch (IOException e) {
            Logger.error(this, "Cannot write location "+l+" to disk: "+e, e);
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
}
