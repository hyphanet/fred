package freenet.keys;

/**
 * Client level CHK. Can be converted into a FreenetURI, can be used to decrypt
 * a ClientCHKBlock, can be produced by a ClientCHKBlock. 
 */
public class ClientCHK {
    
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
    public ClientCHK(FreenetURI uri) {
        if(!uri.getKeyType().equals("CHK"))
            throw new IllegalArgumentException("Not CHK");
        routingKey = uri.getRoutingKey();
        cryptoKey = uri.getCryptoKey();
        // FIXME: flags
        compressed = false;
        controlDocument = false;
        // FIXME: crypto algorithm
        cryptoAlgorithm = ALGO_AES_PCFB_256;
    }

    byte[] routingKey;
    byte[] cryptoKey;
    boolean compressed;
    boolean controlDocument;
    short cryptoAlgorithm;
    
    static final short ALGO_AES_PCFB_256 = 1;

    /**
     * @return a NodeCHK corresponding to this key. Basically keep the 
     * routingKey and lose everything else.
     */
    public NodeCHK getNodeCHK() {
        return new NodeCHK(routingKey);
    }

    /**
     * @return URI form of this key.
     */
    public FreenetURI getURI() {
        return new FreenetURI("CHK", "", routingKey, cryptoKey);
    }
}
