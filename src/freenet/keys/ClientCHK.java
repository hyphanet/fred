package freenet.keys;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;

import freenet.support.Base64;

/**
 * Client level CHK. Can be converted into a FreenetURI, can be used to decrypt
 * a CHKBlock, can be produced by a CHKBlock. 
 */
public class ClientCHK extends ClientKey {
    
	/** Lazily constructed: the NodeCHK */
    NodeCHK nodeKey;
    /** Routing key */
    final byte[] routingKey;
    /** Decryption key */
    final byte[] cryptoKey;
    /** Is the data a control document? */
    final boolean controlDocument;
    /** Encryption algorithm */
    final short cryptoAlgorithm;
    /** Compression algorithm, negative means uncompressed */
    final short compressionAlgorithm;
    
    /* We use EXTRA_LENGTH above for consistency, rather than dis.read etc. Some code depends on this
     * being accurate. Change those uses if you like. */
    /** The length of the "extra" bytes in the key */
    static final short EXTRA_LENGTH = 5;
    /** The length of the decryption key */
    static final short CRYPTO_KEY_LENGTH = 32;
    /** Code for 256-bit AES with PCFB */
    static final short ALGO_AES_PCFB_256 = 1;
    
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
            boolean isControlDocument, short algo, short compressionAlgorithm) {
        this.routingKey = routingKey;
        this.cryptoKey = encKey;
        this.controlDocument = isControlDocument;
        this.cryptoAlgorithm = algo;
        this.compressionAlgorithm = compressionAlgorithm;
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
        if(extra == null || extra.length < 5)
            throw new MalformedURLException();
        cryptoAlgorithm = (short)(((extra[0] & 0xff) << 8) + (extra[1] & 0xff));
		if(cryptoAlgorithm != ALGO_AES_PCFB_256)
			throw new MalformedURLException("Invalid crypto algorithm");
        controlDocument = (extra[2] & 0x02) != 0;
        compressionAlgorithm = (short)(((extra[3] & 0xff) << 8) + (extra[4] & 0xff));
    }

    /**
     * Create from a raw binary CHK. This expresses the key information
     * in as few bytes as possible.
     * @throws IOException 
     */
	private ClientCHK(DataInputStream dis) throws IOException {
		byte[] extra = new byte[EXTRA_LENGTH];
		dis.readFully(extra);
        cryptoAlgorithm = (short)(((extra[0] & 0xff) << 8) + (extra[1] & 0xff));
		if(cryptoAlgorithm != ALGO_AES_PCFB_256)
			throw new MalformedURLException("Invalid crypto algorithm");
        compressionAlgorithm = (short)(((extra[3] & 0xff) << 8) + (extra[4] & 0xff));
        controlDocument = (extra[2] & 0x02) != 0;
		routingKey = new byte[NodeCHK.KEY_LENGTH];
		dis.readFully(routingKey);
		cryptoKey = new byte[CRYPTO_KEY_LENGTH];
		dis.readFully(cryptoKey);
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
    
	public byte[] getExtra() {
		byte[] extra = new byte[EXTRA_LENGTH];
		extra[0] = (byte) (cryptoAlgorithm >> 8);
		extra[1] = (byte) cryptoAlgorithm;
		extra[2] = (byte) (controlDocument ? 2 : 0);
		extra[3] = (byte) (compressionAlgorithm >> 8);
		extra[4] = (byte) compressionAlgorithm;
		return extra;
	}
	
    public String toString() {
        return super.toString()+":"+Base64.encode(routingKey)+","+
        	Base64.encode(cryptoKey)+","+compressionAlgorithm+","+controlDocument+
        	","+cryptoAlgorithm;
    }

	public Key getNodeKey() {
		return getNodeCHK();
	}

	public NodeCHK getNodeCHK() {
		if(nodeKey == null)
	        nodeKey = new NodeCHK(routingKey);
	    return nodeKey;
	}
	
    /**
     * @return URI form of this key.
     */
    public FreenetURI getURI() {
        byte[] extra = getExtra();
        return new FreenetURI("CHK", "", routingKey, cryptoKey, extra);
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
}
