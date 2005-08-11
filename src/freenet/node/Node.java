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
import java.util.HashMap;
import java.util.HashSet;

import freenet.crypt.BlockCipher;
import freenet.crypt.DiffieHellman;
import freenet.crypt.RandomSource;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.Yarrow;
import freenet.crypt.ciphers.Rijndael;
import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.UdpSocketManager;
import freenet.io.xfer.PartiallyReceivedBlock;
import freenet.keys.CHKBlock;
import freenet.keys.CHKVerifyException;
import freenet.keys.ClientCHK;
import freenet.keys.ClientCHKBlock;
import freenet.keys.NodeCHK;
import freenet.store.BaseFreenetStore;
import freenet.store.FreenetStore;
import freenet.support.FileLoggerHook;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

/**
 * @author amphibian
 */
public class Node implements SimpleClient {
    
    public static final int PACKETS_IN_BLOCK = 32;
    public static final int PACKET_SIZE = 1024;
    public static final double DECREMENT_AT_MIN_PROB = 0.2;
    public static final double DECREMENT_AT_MAX_PROB = 0.1;
    // Send keepalives every 30 seconds
    public static final int KEEPALIVE_INTERVAL = 30000;
    // If no activity for 90 seconds, node is dead
    public static final int MAX_PEER_INACTIVITY = 90000;
    /** Time after which a handshake is assumed to have failed. */
    public static final int HANDSHAKE_TIMEOUT = 5000;
    // Inter-handshake time must be at least 2x handshake timeout
    public static final int MIN_TIME_BETWEEN_HANDSHAKE_SENDS = HANDSHAKE_TIMEOUT*2;
    public static final int RANDOMIZED_TIME_BETWEEN_HANDSHAKE_SENDS = HANDSHAKE_TIMEOUT;
    // 900ms
    static final int MIN_INTERVAL_BETWEEN_INCOMING_SWAP_REQUESTS = 900;
    public static final int SYMMETRIC_KEY_LENGTH = 32; // 256 bits - note that this isn't used everywhere to determine it
    
    // FIXME: abstract out address stuff? Possibly to something like NodeReference?
    final int portNumber;
    
    /** These 3 are private because must be protected by synchronized(this) */
    /** The datastore */
    private final FreenetStore datastore;
    /** RequestSender's currently running, by KeyHTLPair */
    private final HashMap requestSenders;
    /** RequestSender's currently transferring, by key */
    private final HashMap transferringRequestSenders;
    /** InsertSender's currently running, by KeyHTLPair */
    private final HashMap insertSenders;
    
    private final HashSet runningUIDs;
    
    byte[] myIdentity; // FIXME: simple identity block; should be unique
    /** Hash of identity. Used as setup key. */
    byte[] identityHash;
    /** Hash of hash of identity i.e. hash of setup key. */
    byte[] identityHashHash; 
    String myName;
    final LocationManager lm;
    final PeerManager peers; // my peers
    final RandomSource random; // strong RNG
    final UdpSocketManager usm;
    final FNPPacketMangler packetMangler;
    final PacketSender ps;
    final NodeDispatcher dispatcher;
    static short MAX_HTL = 20;
    private static final int EXIT_STORE_FILE_NOT_FOUND = 1;
    private static final int EXIT_STORE_IOEXCEPTION = 2;
    private static final int EXIT_STORE_OTHER = 3;
    private static final int EXIT_USM_DIED = 4;
    public static final int EXIT_YARROW_INIT_FAILED = 5;
    public final long bootID;
    
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
        if(identity == null)
            throw new IOException();
        myIdentity = HexUtil.hexToBytes(identity);
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new Error(e);
        }
        identityHash = md.digest(myIdentity);
        identityHashHash = md.digest(identityHash);
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
        myName = fs.get("myName");
        if(myName == null) {
            myName = newName();
        }
    }

    private String newName() {
        return "Node created around "+System.currentTimeMillis();
    }

    private void writeNodeFile(String filename, String backupFilename) throws IOException {
        SimpleFieldSet fs = exportFieldSet();
        File orig = new File(filename);
        File backup = new File(backupFilename);
        orig.renameTo(backup);
        FileOutputStream fos = new FileOutputStream(filename);
        OutputStreamWriter osr = new OutputStreamWriter(fos);
        fs.writeTo(osr);
        osr.close();
    }

    private void initNodeFileSettings(RandomSource r) {
        Logger.normal(this, "Creating new node file from scratch");
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
        identityHashHash = md.digest(identityHash);
        myName = newName();
    }

    /**
     * Read the port number from the arguments.
     * Then create a node.
     */
    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(args[0]);
        System.out.println("Port number: "+port);
        FileLoggerHook logger = new FileLoggerHook("freenet-"+port+".log", "d (c, t, p): m", "MMM dd, yyyy HH:mm:ss:SSS", Logger.MINOR, false, true);
        Logger.setupChain();
        Logger.globalSetThreshold(Logger.MINOR);
        Logger.globalAddHook(logger);
        logger.start();
        Logger.error(Node.class, "Testing...");
        Yarrow yarrow = new Yarrow();
        InetAddress overrideIP = null;
        if(args.length > 1) {
            overrideIP = InetAddress.getByName(args[1]);
            System.err.println("Overriding IP detection: "+overrideIP.getHostAddress());
        }
        DiffieHellman.init(yarrow);
        Node n = new Node(port, yarrow, overrideIP);
        n.start(new StaticSwapRequestInterval(2000));
        new TextModeClientInterface(n);
    }
    
    // FIXME - the whole overrideIP thing is a hack to avoid config
    // Implement the config!
    Node(int port, RandomSource rand, InetAddress overrideIP) {
        portNumber = port;
        this.overrideIPAddress = overrideIP;
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
        random = rand;
        requestSenders = new HashMap();
        transferringRequestSenders = new HashMap();
        insertSenders = new HashMap();
        runningUIDs = new HashSet();

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
        peers = new PeerManager(this, "peers-"+portNumber);
        
        try {
            usm = new UdpSocketManager(portNumber);
            usm.setDispatcher(dispatcher=new NodeDispatcher(this));
            usm.setLowLevelFilter(packetMangler = new FNPPacketMangler(this));
        } catch (SocketException e2) {
            Logger.error(this, "Could not listen for traffic: "+e2, e2);
            System.exit(EXIT_USM_DIED);
            throw new Error();
        }
        decrementAtMax = random.nextDouble() <= DECREMENT_AT_MAX_PROB;
        decrementAtMin = random.nextDouble() <= DECREMENT_AT_MIN_PROB;
        bootID = random.nextLong();
    }

    void start(SwapRequestInterval interval) {
        if(interval != null)
            lm.startSender(this, interval);
        ps.start();
    }
    
    /**
     * Really trivially simple client interface.
     * Either it succeeds or it doesn't.
     */
    public ClientCHKBlock getCHK(ClientCHK key) {
        Object o = makeRequestSender(key.getNodeCHK(), MAX_HTL, random.nextLong(), null);
        if(o instanceof CHKBlock) {
            try {
                return new ClientCHKBlock((CHKBlock)o, key);
            } catch (CHKVerifyException e) {
                Logger.error(this, "Does not verify: "+e, e);
                return null;
            }
        }
        if(o == null) return null;
        RequestSender rs = (RequestSender)o;
        rs.waitUntilFinished();
        if(rs.getStatus() == RequestSender.SUCCESS) {
            try {
                return new ClientCHKBlock(rs.getPRB().getBlock(), rs.getHeaders(), key, true);
            } catch (CHKVerifyException e) {
                Logger.error(this, "Does not verify: "+e, e);
                return null;
            }
        } else {
            Logger.normal(this, "getCHK failed: "+rs.getStatus());
            return null;
        }
    }

    public void putCHK(ClientCHKBlock block) {
        byte[] data = block.getData();
        byte[] headers = block.getHeader();
        PartiallyReceivedBlock prb = new PartiallyReceivedBlock(PACKETS_IN_BLOCK, PACKET_SIZE, data);
        InsertSender is;
        synchronized(this) {
            try {
                datastore.put(block);
            } catch (IOException e) {
                Logger.error(this, "Datastore failure: "+e, e);
            }
            is = makeInsertSender(block.getClientKey().getNodeCHK(), 
                    MAX_HTL, random.nextLong(), null, headers, prb, false);
        }
        is.waitUntilFinished();
        if(is.getStatus() == InsertSender.SUCCESS) {
            Logger.normal(this, "Succeeded inserting "+block);
        } else {
            Logger.error(this, "Failed inserting "+block+" : "+is.getStatus());
        }
    }

    /**
     * Export my reference so that another node can connect to me.
     * @return
     */
    public SimpleFieldSet exportFieldSet() {
        SimpleFieldSet fs = new SimpleFieldSet();
        fs.put("physical.udp", getPrimaryIPAddress().getHostAddress()+":"+portNumber);
        fs.put("identity", HexUtil.bytesToHex(myIdentity));
        fs.put("location", Double.toString(lm.getLocation().getValue()));
        fs.put("version", Version.getVersionString());
        fs.put("myName", myName);
        Logger.minor(this, "My reference: "+fs);
        return fs;
    }

    InetAddress overrideIPAddress;
    
    /**
     * @return Our current main IP address.
     * FIXME - we should support more than 1, and we should do the
     * detection properly with NetworkInterface, and we should use
     * third parties if available and UP&P if available.
     */
    private InetAddress getPrimaryIPAddress() {
        if(overrideIPAddress != null) {
            Logger.minor(this, "Returning overridden address: "+overrideIPAddress);
            return overrideIPAddress;
        }
        Logger.minor(this, "IP address not overridden");
        try {
            // FIXME we should detect this properly
            return InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            Logger.error(this, "Caught "+e+" trying to get localhost!");
            return null;
        }
    }

    /**
     * Do a routed ping of another node on the network by its location.
     * @param loc2 The location of the other node to ping. It must match
     * exactly.
     * @return The number of hops it took to find the node, if it was found.
     * Otherwise -1.
     */
    public int routedPing(double loc2) {
        long uid = random.nextLong();
        int initialX = random.nextInt();
        Message m = DMT.createFNPRoutedPing(uid, loc2, MAX_HTL, initialX);
        Logger.normal(this, "Message: "+m);
        
        dispatcher.handleRouted(m);
        // FIXME: might be rejected
        MessageFilter mf1 = MessageFilter.create().setField(DMT.UID, uid).setType(DMT.FNPRoutedPong).setTimeout(5000);
        //MessageFilter mf2 = MessageFilter.create().setField(DMT.UID, uid).setType(DMT.FNPRoutedRejected).setTimeout(5000);
        // Ignore Rejected - let it be retried on other peers
        m = usm.waitFor(mf1/*.or(mf2)*/);
        if(m == null) return -1;
        if(m.getSpec() == DMT.FNPRoutedRejected) return -1;
        return m.getInt(DMT.COUNTER) - initialX;
    }

    /**
     * Check the datastore, then if the key is not in the store,
     * check whether another node is requesting the same key at
     * the same HTL, and if all else fails, create a new 
     * RequestSender for the key/htl.
     * @return A CHKBlock if the data is in the store, otherwise
     * a RequestSender, unless the HTL is 0, in which case NULL.
     * RequestSender.
     */
    public synchronized Object makeRequestSender(NodeCHK key, short htl, long uid, PeerNode source) {
        Logger.minor(this, "makeRequestSender("+key+","+htl+","+uid+","+source+") on "+portNumber);
        // In store?
        CHKBlock chk = null;
        try {
            chk = datastore.fetch(key);
        } catch (IOException e) {
            Logger.error(this, "Error accessing store: "+e, e);
        }
        if(chk != null) return chk;
        Logger.minor(this, "Not in store locally");
        
        // Transfer coalescing - match key only as HTL irrelevant
        RequestSender sender = (RequestSender) transferringRequestSenders.get(key);
        if(sender != null) {
            Logger.minor(this, "Data already being transferred: "+sender);
            return sender;
        }

        // HTL == 0 => Don't search further
        if(htl == 0) {
            Logger.minor(this, "No HTL");
            return null;
        }
        
        // Request coalescing
        KeyHTLPair kh = new KeyHTLPair(key, htl);
        sender = (RequestSender) requestSenders.get(kh);
        if(sender != null) {
            Logger.minor(this, "Found sender: "+sender);
            return sender;
        }
        
        sender = new RequestSender(key, htl, uid, this, source);
        requestSenders.put(kh, sender);
        Logger.minor(this, "Created new sender: "+sender);
        return sender;
    }
    
    class KeyHTLPair {
        final NodeCHK key;
        final short htl;
        KeyHTLPair(NodeCHK key, short htl) {
            this.key = key;
            this.htl = htl;
        }
        
        public boolean equals(Object o) {
            if(o instanceof KeyHTLPair) {
                KeyHTLPair p = (KeyHTLPair) o;
                return (p.key == key && p.htl == htl);
            } else return false;
        }
        
        public int hashCode() {
            return key.hashCode() ^ htl;
        }
    }

    /**
     * Add a RequestSender to our HashSet.
     */
    public synchronized void addSender(NodeCHK key, short htl, RequestSender sender) {
        KeyHTLPair kh = new KeyHTLPair(key, htl);
        requestSenders.put(kh, sender);
    }

    /**
     * Add a transferring RequestSender.
     */
    public synchronized void addTransferringSender(NodeCHK key, RequestSender sender) {
        transferringRequestSenders.put(key, sender);
    }

    /**
     * Store a datum.
     */
    public synchronized void store(CHKBlock block) {
        try {
            datastore.put(block);
        } catch (IOException e) {
            Logger.error(this, "Cannot store data: "+e, e);
        }
    }

    public synchronized CHKBlock fetchFromStore(NodeCHK key) {
        try {
            return datastore.fetch(key);
        } catch (IOException e) {
            Logger.error(this, "Cannot fetch: "+e, e);
            return null;
        }
    }
    
    /**
     * Remove a sender from the set of currently transferring senders.
     */
    public synchronized void removeTransferringSender(NodeCHK key, RequestSender sender) {
        RequestSender rs = (RequestSender) transferringRequestSenders.remove(key);
        if(rs != sender) {
            Logger.error(this, "Removed "+rs+" should be "+sender+" for "+key+" in removeTransferringSender");
        }
    }

    /**
     * Remove a RequestSender from the map.
     */
    public synchronized void removeSender(NodeCHK key, short htl, RequestSender sender) {
        KeyHTLPair kh = new KeyHTLPair(key, htl);
        RequestSender rs = (RequestSender) requestSenders.remove(kh);
        if(rs != sender) {
            Logger.error(this, "Removed "+rs+" should be "+sender+" for "+key+","+htl+" in removeSender");
        }
    }

    /**
     * Remove an InsertSender from the map.
     */
    public void removeInsertSender(NodeCHK key, short htl, InsertSender sender) {
        KeyHTLPair kh = new KeyHTLPair(key, htl);
        InsertSender is = (InsertSender) insertSenders.remove(kh);
        if(is != sender) {
            Logger.error(this, "Removed "+is+" should be "+sender+" for "+key+","+htl+" in removeSender");
        }
    }

    final boolean decrementAtMax;
    final boolean decrementAtMin;
    
    /**
     * Decrement the HTL according to the policy of the given
     * NodePeer if it is non-null, or do something else if it is
     * null.
     */
    public short decrementHTL(PeerNode source, short htl) {
        if(source != null)
            return source.decrementHTL(htl);
        // Otherwise...
        if(htl >= MAX_HTL) htl = MAX_HTL;
        if(htl <= 0) htl = 1;
        if(htl == MAX_HTL) {
            if(decrementAtMax) htl--;
            return htl;
        }
        if(htl == 1) {
            if(decrementAtMin) htl--;
            return htl;
        }
        return --htl;
    }

    /**
     * Fetch or create an InsertSender for a given key/htl.
     * @param key The key to be inserted.
     * @param htl The current HTL. We can't coalesce inserts across
     * HTL's.
     * @param uid The UID of the caller's request chain, or a new
     * one. This is obviously not used if there is already an 
     * InsertSender running.
     * @param source The node that sent the InsertRequest, or null
     * if it originated locally.
     */
    public synchronized InsertSender makeInsertSender(NodeCHK key, short htl, long uid, PeerNode source,
            byte[] headers, PartiallyReceivedBlock prb, boolean fromStore) {
        Logger.minor(this, "makeInsertSender("+key+","+htl+","+uid+","+source+",...,"+fromStore);
        KeyHTLPair kh = new KeyHTLPair(key, htl);
        InsertSender is = (InsertSender) insertSenders.get(kh);
        if(is != null) return is;
        is = new InsertSender(key, uid, headers, htl, source, this, prb, fromStore);
        insertSenders.put(kh, is);
        return is;
    }
    
    public boolean lockUID(long uid) {
        Long l = new Long(uid);
        synchronized(runningUIDs) {
            if(runningUIDs.contains(l)) return false;
            runningUIDs.add(l);
            return true;
        }
    }
    
    public void unlockUID(long uid) {
        Long l = new Long(uid);
        synchronized(runningUIDs) {
            if(!runningUIDs.remove(l))
                throw new IllegalStateException("Could not unlock "+uid+"!");
        }
    }

    /**
     * @return Some status information.
     */
    public String getStatus() {
        return peers.getStatus();
    }
}
