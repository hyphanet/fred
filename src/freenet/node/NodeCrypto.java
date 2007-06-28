package freenet.node;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.security.MessageDigest;

import net.i2p.util.NativeBigInteger;

import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.crypt.DSA;
import freenet.crypt.DSAGroup;
import freenet.crypt.DSAPrivateKey;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.DSASignature;
import freenet.crypt.Global;
import freenet.crypt.RandomSource;
import freenet.crypt.SHA256;
import freenet.io.comm.UdpSocketHandler;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.support.Base64;
import freenet.support.Fields;
import freenet.support.IllegalBase64Exception;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.IntCallback;
import freenet.support.api.StringCallback;

/**
 * Cryptographic and transport level node identity. 
 * @author toad
 */
class NodeCrypto {

	final RandomSource random;
	/** The object which handles our specific UDP port, pulls messages from it, feeds them to the packet mangler for decryption etc */
	UdpSocketHandler socket;
	public FNPPacketMangler packetMangler;
	final String bindto;
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
	
	/**
	 * Get port number from a config, create socket and packet mangler
	 * @throws NodeInitException 
	 */
	public NodeCrypto(SubConfig nodeConfig, int sortOrder, Node node) throws NodeInitException {
		
		random = node.random;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		
		nodeConfig.register("listenPort", -1 /* means random */, sortOrder++, true, true, "Node.port", "Node.portLong",	new IntCallback() {
			public int get() {
				return portNumber;
			}
			public void set(int val) throws InvalidConfigValueException {
				// FIXME implement on the fly listenPort changing
				// Note that this sort of thing should be the exception rather than the rule!!!!
				String msg = "Switching listenPort on the fly not yet supported!";
				Logger.error(this, msg);
				throw new InvalidConfigValueException(msg);
			}
		});
		
		int port=-1;
		try{
			port=nodeConfig.getInt("listenPort");
		}catch (Exception e){
			Logger.error(this, "Caught "+e, e);
			System.err.println(e);
			e.printStackTrace();
			port=-1;
		}
		
		nodeConfig.register("bindTo", "0.0.0.0", sortOrder++, true, true, "Node.bindTo", "Node.bindToLong", new NodeBindtoCallback());
		
		this.bindto = nodeConfig.getString("bindTo");
		
		UdpSocketHandler u = null;
		
		if(port > 65535) {
			throw new NodeInitException(NodeInitException.EXIT_IMPOSSIBLE_USM_PORT, "Impossible port number: "+port);
		} else if(port == -1) {
			// Pick a random port
			for(int i=0;i<200000;i++) {
				int portNo = 1024 + random.nextInt(65535-1024);
				try {
					u = new UdpSocketHandler(portNo, InetAddress.getByName(bindto), node);
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
				u = new UdpSocketHandler(port, InetAddress.getByName(bindto), node);
			} catch (Exception e) {
				throw new NodeInitException(NodeInitException.EXIT_IMPOSSIBLE_USM_PORT, "Could not bind to port: "+port+" (node already running?)");
			}
		}
		socket = u;
		
		Logger.normal(this, "FNP port created on "+bindto+ ':' +port);
		System.out.println("FNP port created on "+bindto+ ':' +port);
		portNumber = port;
		
		nodeConfig.register("testingDropPacketsEvery", 0, sortOrder++, true, false, "Node.dropPacketEvery", "Node.dropPacketEveryLong",
				new IntCallback() {

					public int get() {
						return ((UdpSocketHandler)socket).getDropProbability();
					}

					public void set(int val) throws InvalidConfigValueException {
						((UdpSocketHandler)socket).setDropProbability(val);
					}
			
		});
		
		int dropProb = nodeConfig.getInt("testingDropPacketsEvery");
		((UdpSocketHandler)socket).setDropProbability(dropProb);
		
		socket.setLowLevelFilter(packetMangler = new FNPPacketMangler(node, this, socket));
	}
	
	class NodeBindtoCallback implements StringCallback {
		
		public String get() {
			return bindto;
		}
		
		public void set(String val) throws InvalidConfigValueException {
			if(val.equals(get())) return;
			// FIXME why not? Can't we use freenet.io.NetworkInterface like everywhere else, just adapt it for UDP?
			throw new InvalidConfigValueException("Cannot be updated on the fly");
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

	SimpleFieldSet exportPublicFieldSet(boolean forSetup) {
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

	void addPrivateFields(SimpleFieldSet fs) {
		fs.put("dsaPrivKey", privKey.asFieldSet());
		fs.putSingle("ark.privURI", myARK.getInsertURI().toString(false, false));
	}

	public String getBindTo(){
		return this.bindto;
	}
	
	public int getIdentityHash(){
		return Fields.hashCode(identityHash);
	}

	/** Sign a hash */
	DSASignature sign(byte[] hash) {
		return DSA.sign(cryptoGroup, privKey, new NativeBigInteger(1, hash), random);
	}
	
}
