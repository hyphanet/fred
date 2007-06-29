/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.security.MessageDigest;
import java.util.zip.DeflaterOutputStream;

import net.i2p.util.NativeBigInteger;
import freenet.crypt.DSA;
import freenet.crypt.DSAGroup;
import freenet.crypt.DSAPrivateKey;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.DSASignature;
import freenet.crypt.Global;
import freenet.crypt.RandomSource;
import freenet.crypt.SHA256;
import freenet.io.comm.Peer;
import freenet.io.comm.UdpSocketHandler;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.support.Base64;
import freenet.support.Fields;
import freenet.support.IllegalBase64Exception;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

/**
 * Cryptographic and transport level node identity. 
 * @author toad
 */
class NodeCrypto {

	final Node node;
	final boolean isOpennet;
	final RandomSource random;
	/** The object which handles our specific UDP port, pulls messages from it, feeds them to the packet mangler for decryption etc */
	UdpSocketHandler socket;
	public FNPPacketMangler packetMangler;
	final InetAddress bindto;
	// FIXME: abstract out address stuff? Possibly to something like NodeReference?
	final int portNumber;
	byte[] myIdentity; // FIXME: simple identity block; should be unique
	/** Hash of identity. Used as setup key. */
	byte[] identityHash;
	/** Hash of hash of identity i.e. hash of setup key. */
	byte[] identityHashHash;
	/** My crypto group */
	private DSAGroup cryptoGroup;
	/** My private key */
	private DSAPrivateKey privKey;
	/** My public key */
	private DSAPublicKey pubKey;
	/** My ARK SSK private key */
	InsertableClientSSK myARK;
	/** My ARK sequence number */
	long myARKNumber;
	static boolean logMINOR;
	final NodeCryptoConfig config;
	
	// Noderef related
	/** The signature of the above fieldset */
	private DSASignature myReferenceSignature = null;
	/** A synchronization object used while signing the reference fiedlset */
	private volatile Object referenceSync = new Object();
	/** An ordered version of the FieldSet, without the signature */
	private String mySignedReference = null;
	
	/**
	 * Get port number from a config, create socket and packet mangler
	 * @throws NodeInitException 
	 */
	public NodeCrypto(int sortOrder, Node node, boolean isOpennet, NodeCryptoConfig config) throws NodeInitException {

		this.node = node;
		this.config = config;
		random = node.random;
		this.isOpennet = isOpennet;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		
		config.starting(this);
		
		try {
		
		int port = config.getPort();
		
		bindto = config.getBindTo();
		
		UdpSocketHandler u = null;
		
		if(port > 65535) {
			throw new NodeInitException(NodeInitException.EXIT_IMPOSSIBLE_USM_PORT, "Impossible port number: "+port);
		} else if(port == -1) {
			// Pick a random port
			for(int i=0;i<200000;i++) {
				int portNo = 1024 + random.nextInt(65535-1024);
				try {
					u = new UdpSocketHandler(portNo, bindto, node);
					port = u.getPortNumber();
					break;
				} catch (Exception e) {
					Logger.normal(this, "Could not use port: "+bindto+ ':' +portNo+": "+e, e);
					System.err.println("Could not use port: "+bindto+ ':' +portNo+": "+e);
					e.printStackTrace();
					continue;
				}
			}
			if(u == null)
				throw new NodeInitException(NodeInitException.EXIT_NO_AVAILABLE_UDP_PORTS, "Could not find an available UDP port number for FNP (none specified)");
		} else {
			try {
				u = new UdpSocketHandler(port, bindto, node);
			} catch (Exception e) {
				throw new NodeInitException(NodeInitException.EXIT_IMPOSSIBLE_USM_PORT, "Could not bind to port: "+port+" (node already running?)");
			}
		}
		socket = u;
		
		Logger.normal(this, "FNP port created on "+bindto+ ':' +port);
		System.out.println("FNP port created on "+bindto+ ':' +port);
		portNumber = port;
		config.setPort(port);
		
		((UdpSocketHandler)socket).setDropProbability(config.getDropProbability());
		
		socket.setLowLevelFilter(packetMangler = new FNPPacketMangler(node, this, socket));
		} catch (NodeInitException e) {
			config.stopping(this);
			throw e;
		} catch (RuntimeException e) {
			config.stopping(this);
			throw e;
		} catch (Error e) {
			config.stopping(this);
			throw e;
		} finally {
			config.maybeStarted(this);
		}
	}
	
	/**
	 * Read the cryptographic keys etc from a SimpleFieldSet
	 * @param fs
	 * @throws IOException 
	 */
	public void readCrypto(SimpleFieldSet fs) throws IOException {
		String identity = fs.get("identity");
		if(identity == null)
			throw new IOException();
		try {
			myIdentity = Base64.decode(identity);
		} catch (IllegalBase64Exception e2) {
			throw new IOException();
		}
		identityHash = SHA256.digest(myIdentity);
		identityHashHash = SHA256.digest(identityHash);
		
		try {
			cryptoGroup = DSAGroup.create(fs.subset("dsaGroup"));
			privKey = DSAPrivateKey.create(fs.subset("dsaPrivKey"), cryptoGroup);
			pubKey = DSAPublicKey.create(fs.subset("dsaPubKey"), cryptoGroup);
		} catch (IllegalBase64Exception e) {
			if(logMINOR) Logger.minor(this, "Caught "+e, e);
			this.cryptoGroup = Global.DSAgroupBigA;
			this.privKey = new DSAPrivateKey(cryptoGroup, random);
			this.pubKey = new DSAPublicKey(cryptoGroup, privKey);
		}
		InsertableClientSSK ark = null;
		
		// ARK
		
		String s = fs.get("ark.number");
		
		String privARK = fs.get("ark.privURI");
		try {
			if(privARK != null) {
				FreenetURI uri = new FreenetURI(privARK);
				ark = InsertableClientSSK.create(uri);
				if(ark.isInsecure() || s == null) {
					if(ark.isInsecure())
						System.out.println("Creating new ARK, old is insecure");
					ark = null;
					myARKNumber = 0;
				} else {
					try {
						myARKNumber = Long.parseLong(s);
					} catch (NumberFormatException e) {
						myARKNumber = 0;
						ark = null;
					}
				}
			}
		} catch (MalformedURLException e) {
			Logger.minor(this, "Caught "+e, e);
			ark = null;
		}
		if(ark == null) {
			ark = InsertableClientSSK.createRandom(random, "ark");
			myARKNumber = 0;
		}
		myARK = ark;

	}

	/**
	 * Create the cryptographic keys etc from scratch
	 */
	public void initCrypto() {
		myIdentity = new byte[32];
		random.nextBytes(myIdentity);
		MessageDigest md = SHA256.getMessageDigest();
		identityHash = md.digest(myIdentity);
		identityHashHash = md.digest(identityHash);
		cryptoGroup = Global.DSAgroupBigA;
		privKey = new DSAPrivateKey(cryptoGroup, random);
		pubKey = new DSAPublicKey(cryptoGroup, privKey);
		myARK = InsertableClientSSK.createRandom(random, "ark");
		myARKNumber = 0;
		SHA256.returnMessageDigest(md);
	}

	public void start(boolean disableHangchecker) {
		socket.start(disableHangchecker);
	}
	
	public SimpleFieldSet exportPrivateFieldSet() {
		SimpleFieldSet fs = exportPublicFieldSet(false);
		addPrivateFields(fs);
		return fs;
	}
	
	/**
	 * Export my node reference so that another node can connect to me.
	 * Public version, includes everything apart from private keys.
	 * @see exportPublicFieldSet(boolean forSetup).
	 */
	public SimpleFieldSet exportPublicFieldSet() {
		return exportPublicFieldSet(false);
	}
	
	/**
	 * Export my reference so that another node can connect to me.
	 * @param forSetup If true, strip out everything that isn't needed for the references
	 * exchanged immediately after connection setup. I.e. strip out everything that is invariant,
	 * or that can safely be exchanged later.
	 */
	SimpleFieldSet exportPublicFieldSet(boolean forSetup) {
		SimpleFieldSet fs = exportPublicCryptoFieldSet(forSetup);
		// IP addresses
		Peer[] ips = node.ipDetector.getPrimaryIPAddress();
		if(ips != null) {
			for(int i=0;i<ips.length;i++)
				fs.putAppend("physical.udp", ips[i].toString()); // Keep; important that node know all our IPs
		}
		// Negotiation types
		fs.put("location", node.lm.getLocation().getValue()); // FIXME maybe !forSetup; see #943
		fs.putSingle("version", Version.getVersionString()); // Keep, vital that peer know our version. For example, some types may be sent in different formats to different node versions (e.g. Peer).
		fs.put("testnet", node.testnetEnabled); // Vital that peer know this!
		fs.putSingle("lastGoodVersion", Version.getLastGoodVersionString()); // Also vital
		if(node.testnetEnabled)
			fs.put("testnetPort", node.testnetHandler.getPort()); // Useful, saves a lot of complexity
		if(!isOpennet)
			fs.putSingle("myName", node.getMyName()); // FIXME see #942
		
		synchronized (referenceSync) {
			if(myReferenceSignature == null || mySignedReference == null || !mySignedReference.equals(fs.toOrderedString())){
				mySignedReference = fs.toOrderedString();
				try {
					myReferenceSignature = signRef(mySignedReference);
				} catch (NodeInitException e) {
					node.exit(e.exitCode);
				}
			}
			fs.putSingle("sig", myReferenceSignature.toLongString());
		}
		fs.put("opennet", isOpennet);
		
		if(logMINOR) Logger.minor(this, "My reference: "+fs.toOrderedString());
		return fs;
	}

	SimpleFieldSet exportPublicCryptoFieldSet(boolean forSetup) {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		int[] negTypes = packetMangler.supportedNegTypes();
		fs.put("auth.negTypes", negTypes);
		fs.putSingle("identity", Base64.encode(myIdentity)); // FIXME !forSetup after 11104 is mandatory
		if(!forSetup) {
			// These are invariant. They cannot change on connection setup. They can safely be excluded.
			fs.put("dsaGroup", cryptoGroup.asFieldSet());
			fs.put("dsaPubKey", pubKey.asFieldSet());
		}
		fs.put("ark.number", myARKNumber); // Can be changed on setup
		fs.putSingle("ark.pubURI", myARK.getURI().toString(false, false)); // Can be changed on setup
		return fs;
	}

	DSASignature signRef(String mySignedReference) throws NodeInitException {
		if(logMINOR) Logger.minor(this, "Signing reference:\n"+mySignedReference);

		try{
			byte[] ref = mySignedReference.getBytes("UTF-8");
			BigInteger m = new BigInteger(1, SHA256.digest(ref));
			if(logMINOR) Logger.minor(this, "m = "+m.toString(16));
			DSASignature myReferenceSignature = DSA.sign(cryptoGroup, privKey, m, random);
			// FIXME remove this ... eventually
			if(!DSA.verify(pubKey, myReferenceSignature, m, false))
				Logger.error(this, "Signature failed!");
			return myReferenceSignature;
		} catch(UnsupportedEncodingException e){
			//duh ?
			Logger.error(this, "Error while signing the node identity!"+e);
			System.err.println("Error while signing the node identity!"+e);
			e.printStackTrace();
			throw new NodeInitException(NodeInitException.EXIT_CRAPPY_JVM, "UTF-8 not supported!");
		}
	}

	/**
	 * The part of our node reference which is exchanged in the connection setup, compressed.
	 * @see exportSetupFieldSet()
	 */
	public byte[] myCompressedSetupRef() {
		SimpleFieldSet fs = exportPublicFieldSet(true);
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DeflaterOutputStream gis;
		gis = new DeflaterOutputStream(baos);
		OutputStreamWriter osw = new OutputStreamWriter(gis);
		BufferedWriter bw = new BufferedWriter(osw);
		try {
			fs.writeTo(bw);
		} catch (IOException e) {
			throw new Error(e);
		}
		try {
			bw.close();
		} catch (IOException e1) {
			throw new Error(e1);
		}
		byte[] buf = baos.toByteArray();
		byte[] obuf = new byte[buf.length + 1];
		obuf[0] = 1;
		System.arraycopy(buf, 0, obuf, 1, buf.length);
		return obuf;
	}

	void addPrivateFields(SimpleFieldSet fs) {
		fs.put("dsaPrivKey", privKey.asFieldSet());
		fs.putSingle("ark.privURI", myARK.getInsertURI().toString(false, false));
	}

	public int getIdentityHash(){
		return Fields.hashCode(identityHash);
	}

	/** Sign a hash */
	DSASignature sign(byte[] hash) {
		return DSA.sign(cryptoGroup, privKey, new NativeBigInteger(1, hash), random);
	}

	public void onSetDropProbability(int val) {
		synchronized(this) {
			if(socket == null) return;
		}
		socket.setDropProbability(val);
	}

	public void stop() {
		socket.close(true);
		config.stopping(this);
	}
	
}
