/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.keys;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.HashSet;

import com.db4o.ObjectContainer;

import freenet.support.Base64;
import freenet.support.ByteArrayWrapper;
import freenet.support.Fields;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;

/**
 * Client level CHK. Can be converted into a FreenetURI, can be used to decrypt
 * a CHKBlock, can be produced by a CHKBlock. 
 */
public class ClientCHK extends ClientKey {
    
	/** Lazily constructed: the NodeCHK */
    transient NodeCHK nodeKey;
    /** Routing key */
    final byte[] routingKey;
    /** Decryption key */
    final byte[] cryptoKey;
    /** Is the data a control document? */
    final boolean controlDocument;
    /** Encryption algorithm */
    final byte cryptoAlgorithm;
    /** Compression algorithm, negative means uncompressed */
    final short compressionAlgorithm;
    final int hashCode;
    
    /* We use EXTRA_LENGTH above for consistency, rather than dis.read etc. Some code depends on this
     * being accurate. Change those uses if you like. */
    /** The length of the "extra" bytes in the key */
    public static final short EXTRA_LENGTH = 5;
    /** The length of the decryption key */
    public static final short CRYPTO_KEY_LENGTH = 32;
    
    private ClientCHK(ClientCHK key) {
    	this.routingKey = key.routingKey.clone();
    	this.nodeKey = null;
    	this.cryptoKey = key.cryptoKey.clone();
    	this.controlDocument = key.controlDocument;
    	this.cryptoAlgorithm = key.cryptoAlgorithm;
    	this.compressionAlgorithm = key.compressionAlgorithm;
        if(routingKey == null) throw new NullPointerException();
        hashCode = Fields.hashCode(routingKey) ^ Fields.hashCode(routingKey) ^ compressionAlgorithm;
    }
    
    /**
     * @param routingKey The routing key. This is the overall hash of the
     * header and content of the key.
     * @param encKey The decryption key. This is not passed to other nodes
     * and is extracted from the URI.
     * @param isCompressed True if the data was gzipped before encoding.
     * @param isControlDocument True if the document is a Control Document.
     * These carry metadata, whereas ordinary keys carry data, and have no
     * type.
     * @param algo The encryption algorithm's identifier. See ALGO_* for 
     * values.
     */
    public ClientCHK(byte[] routingKey, byte[] encKey,  
            boolean isControlDocument, byte algo, short compressionAlgorithm) {
        this.routingKey = routingKey;
        this.cryptoKey = encKey;
        this.controlDocument = isControlDocument;
        this.cryptoAlgorithm = algo;
        this.compressionAlgorithm = compressionAlgorithm;
        if(routingKey == null) throw new NullPointerException();
        hashCode = Fields.hashCode(routingKey) ^ Fields.hashCode(encKey) ^ compressionAlgorithm;
    }

    public ClientCHK(byte[] routingKey, byte[] encKey, byte[] extra) throws MalformedURLException {
    	this.routingKey = routingKey;
    	this.cryptoKey = encKey;
        if((extra == null) || (extra.length < 5))
            throw new MalformedURLException("No extra bytes in CHK - maybe a 0.5 key?");
        // byte 0 is reserved, for now
        cryptoAlgorithm = extra[1];
		if(!(cryptoAlgorithm == Key.ALGO_AES_PCFB_256_SHA256 || cryptoAlgorithm == Key.ALGO_AES_CTR_256_SHA256))
			throw new MalformedURLException("Invalid crypto algorithm");
        controlDocument = (extra[2] & 0x02) != 0;
        compressionAlgorithm = (short)(((extra[3] & 0xff) << 8) + (extra[4] & 0xff));
        hashCode = Fields.hashCode(routingKey) ^ Fields.hashCode(cryptoKey) ^ compressionAlgorithm;
    }
    
    /**
     * Create from a URI.
     */
    public ClientCHK(FreenetURI uri) throws MalformedURLException {
        if(!uri.getKeyType().equals("CHK"))
            throw new MalformedURLException("Not CHK");
        routingKey = uri.getRoutingKey();
        cryptoKey = uri.getCryptoKey();
        byte[] extra = uri.getExtra();
        if((extra == null) || (extra.length < 5))
            throw new MalformedURLException("No extra bytes in CHK - maybe a 0.5 key?");
        // byte 0 is reserved, for now
        cryptoAlgorithm = extra[1];
		if(!(cryptoAlgorithm == Key.ALGO_AES_PCFB_256_SHA256 || cryptoAlgorithm == Key.ALGO_AES_CTR_256_SHA256))
			throw new MalformedURLException("Invalid crypto algorithm");
        controlDocument = (extra[2] & 0x02) != 0;
        compressionAlgorithm = (short)(((extra[3] & 0xff) << 8) + (extra[4] & 0xff));
        hashCode = Fields.hashCode(routingKey) ^ Fields.hashCode(cryptoKey) ^ compressionAlgorithm;
    }

    /**
     * Create from a raw binary CHK. This expresses the key information
     * in as few bytes as possible.
     * @throws IOException 
     */
	private ClientCHK(DataInputStream dis) throws IOException {
		byte[] extra = new byte[EXTRA_LENGTH];
		dis.readFully(extra);
		// byte 0 is reserved, for now
        cryptoAlgorithm = extra[1];
		if(!(cryptoAlgorithm == Key.ALGO_AES_PCFB_256_SHA256 || cryptoAlgorithm == Key.ALGO_AES_CTR_256_SHA256))
			throw new MalformedURLException("Invalid crypto algorithm");
        compressionAlgorithm = (short)(((extra[3] & 0xff) << 8) + (extra[4] & 0xff));
        controlDocument = (extra[2] & 0x02) != 0;
		routingKey = new byte[NodeCHK.KEY_LENGTH];
		dis.readFully(routingKey);
		cryptoKey = new byte[CRYPTO_KEY_LENGTH];
		dis.readFully(cryptoKey);
        hashCode = Fields.hashCode(routingKey) ^ Fields.hashCode(cryptoKey) ^ compressionAlgorithm;
	}

	/**
	 * Write an ultra-compact representation.
	 * @throws IOException If a write failed.
	 */
	public void writeRawBinaryKey(DataOutputStream dos) throws IOException {
		dos.write(getExtra());
		dos.write(routingKey);
		dos.write(cryptoKey);
	}
    
	static byte[] lastExtra;
	
	public byte[] getExtra() {
		return getExtra(cryptoAlgorithm, compressionAlgorithm, controlDocument);
	}
	
	public static byte[] getExtra(byte cryptoAlgorithm, short compressionAlgorithm, boolean controlDocument) {
		byte[] extra = new byte[EXTRA_LENGTH];
		extra[0] = (byte) (cryptoAlgorithm >> 8);
		extra[1] = cryptoAlgorithm;
		extra[2] = (byte) (controlDocument ? 2 : 0);
		extra[3] = (byte) (compressionAlgorithm >> 8);
		extra[4] = (byte) compressionAlgorithm;
		byte[] last = lastExtra;
		// No synchronization required IMHO
		if(Arrays.equals(last, extra)) return last;
		lastExtra = extra;
		return extra;
	}
	
	public static byte getCryptoAlgorithmFromExtra(byte[] extra) {
		return extra[1];
	}
	
	static HashSet<ByteArrayWrapper> standardExtras = new HashSet<ByteArrayWrapper>();
	static {
		for(byte cryptoAlgorithm = Key.ALGO_AES_PCFB_256_SHA256; cryptoAlgorithm <= Key.ALGO_AES_CTR_256_SHA256; cryptoAlgorithm++) {
			for(short compressionAlgorithm = -1; compressionAlgorithm <= (short)(COMPRESSOR_TYPE.countCompressors()); compressionAlgorithm++) {
				standardExtras.add(new ByteArrayWrapper(getExtra(cryptoAlgorithm, compressionAlgorithm, true)));
				standardExtras.add(new ByteArrayWrapper(getExtra(cryptoAlgorithm, compressionAlgorithm, false)));
			}
		}
	}
	
	public static byte[] internExtra(byte[] extra) {
		for(ByteArrayWrapper baw : standardExtras) {
			if(Arrays.equals(baw.get(), extra)) return baw.get();
		}
		return extra;
	}
	
    @Override
	public String toString() {
        return super.toString()+ ':' +Base64.encode(routingKey)+ ',' +
        	Base64.encode(cryptoKey)+ ',' +compressionAlgorithm+ ',' +controlDocument+
                ',' +cryptoAlgorithm;
    }

	@Override
	public Key getNodeKey(boolean cloneKey) {
		return cloneKey ? getNodeCHK().cloneKey() : getNodeCHK();
	}

	public synchronized NodeCHK getNodeCHK() {
		// This costs us more or less nothing: we have to keep the routingKey anyway.
		// Therefore, keeping a NodeCHK as well is a net saving, since it's frequently
		// asked for. (A SoftReference would cost more).
		if(nodeKey == null)
	        nodeKey = new NodeCHK(routingKey, cryptoAlgorithm);
	    return nodeKey;
	}
	
    /**
     * @return URI form of this key.
     */
    @Override
	public FreenetURI getURI() {
        byte[] extra = getExtra();
        return new FreenetURI("CHK", null, routingKey, cryptoKey, extra);
    }

    /**
     * Read a raw binary CHK. This is an ultra-compact representation, for
     * splitfile metadata etc.
     */
	public static ClientCHK readRawBinaryKey(DataInputStream dis) throws IOException {
		return new ClientCHK(dis);
	}

	public boolean isMetadata() {
		return controlDocument;
	}

	public boolean isCompressed() {
		return compressionAlgorithm >= 0;
	}

	@Override
	public ClientCHK cloneKey() {
		return new ClientCHK(this);
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		container.delete(this);
	}
	
	@Override
	public int hashCode() {
		return hashCode;
	}
	
	@Override
	public boolean equals(Object o) {
		if(!(o instanceof ClientCHK)) return false;
		ClientCHK key = (ClientCHK) o;
		if(controlDocument != key.controlDocument) return false;
		if(cryptoAlgorithm != key.cryptoAlgorithm) return false;
		if(compressionAlgorithm != key.compressionAlgorithm) return false;
		if(!Arrays.equals(routingKey, key.routingKey)) return false;
		if(!Arrays.equals(cryptoKey, key.cryptoKey)) return false;
		return true;
	}

	public byte[] getRoutingKey() {
		return routingKey;
	}
	
	public byte[] getCryptoKey() {
		return cryptoKey;
	}
	
	public boolean objectCanNew(ObjectContainer container) {
		if(routingKey == null)
			throw new NullPointerException("Storing a ClientCHK with no routingKey!: stored="+container.ext().isStored(this)+" active="+container.ext().isActive(this));
		if(cryptoKey == null)
			throw new NullPointerException("Storing a ClientCHK with no cryptoKey!");
		return true;
	}
}
