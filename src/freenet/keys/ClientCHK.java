package freenet.keys;

import java.net.MalformedURLException;

import freenet.support.Base64;

/**
 * Client level CHK. Can be converted into a FreenetURI, can be used to decrypt
 * a CHKBlock, can be produced by a CHKBlock. 
 */
public class ClientCHK {
    
    NodeCHK nodeKey;
    
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
    public ClientCHK(byte[] routingKey, byte[] encKey, boolean isCompressed, 
            boolean isControlDocument, short algo) {
        this.routingKey = routingKey;
        this.cryptoKey = encKey;
        this.compressed = isCompressed;
        this.controlDocument = isControlDocument;
        this.cryptoAlgorithm = algo;
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
        if(extra == null || extra.length < 3)
            throw new MalformedURLException();
        cryptoAlgorithm = (short)(((extra[0] & 0xff) << 8) + (extra[1] & 0xff));
        compressed = (extra[2] & 0x01) == 1;
        controlDocument = (extra[2] & 0x02) == 1;
    }

    byte[] routingKey;
    byte[] cryptoKey;
    boolean compressed;
    boolean controlDocument;
    short cryptoAlgorithm;
    
    public String toString() {
        return super.toString()+":"+Base64.encode(routingKey)+","+
        	Base64.encode(cryptoKey)+","+compressed+","+controlDocument+
        	","+cryptoAlgorithm;
    }
    
    static final short ALGO_AES_PCFB_256 = 1;

    /**
     * @return a NodeCHK corresponding to this key. Basically keep the 
     * routingKey and lose everything else.
     */
    public NodeCHK getNodeCHK() {
        if(nodeKey == null)
            nodeKey = new NodeCHK(routingKey);
        return nodeKey;
    }

    /**
     * @return URI form of this key.
     */
    public FreenetURI getURI() {
        byte[] extra = new byte[3];
        extra[0] = (byte)((cryptoAlgorithm >> 8) & 0xff);
        extra[1] = (byte)(cryptoAlgorithm & 0xff);
        extra[2] = 
            (byte)((compressed ? 1 : 0) + (controlDocument ? 2 : 0));
        return new FreenetURI("CHK", "", routingKey, cryptoKey, extra);
    }
}
