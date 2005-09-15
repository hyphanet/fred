package freenet.keys;

import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.Random;

import org.spaceroots.mantissa.random.MersenneTwister;

import freenet.crypt.PCFBMode;
import freenet.crypt.RandomSource;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;
import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.Logger;

/**
 * Key for a publish/subscribe stream. Contains a pubkey,
 * a routing key, and a symmetric crypto key.
 */
public class ClientPublishStreamKey {
    /** The maximum number of bytes in a single packet */
    public final static int SIZE_MAX = 1024;
    static final int SYMMETRIC_KEYLENGTH = 32;
    
    /** The routing key */
    private final byte[] routingKey;
    
    /** The content encryption/decryption key */
    private final byte[] symCipherKey;

    /** The encryption/decryption algorithm */
    private final short cryptoAlgorithm;
    
    private final int hashCode;

    private static final int ROUTING_KEY_LENGTH = 20;
    private static final int SYM_CIPHER_KEY_LENGTH = 32;
    
    static final short ALGO_AES_PCFB_256 = 1;

    private ClientPublishStreamKey(byte[] routingKey2, byte[] symCipherKey2) {
        routingKey = routingKey2;
        symCipherKey = symCipherKey2;
        hashCode = Fields.hashCode(routingKey) ^ Fields.hashCode(symCipherKey);
        cryptoAlgorithm = ALGO_AES_PCFB_256;
    }

    /**
     * @param uri
     */
    public ClientPublishStreamKey(FreenetURI uri) throws MalformedURLException {
        if(!uri.getKeyType().equals("PSK"))
            throw new MalformedURLException("Not PSK");
        routingKey = uri.getRoutingKey();
        symCipherKey = uri.getCryptoKey();
        byte[] extra = uri.getExtra();
        if(extra == null || extra.length < 2)
            throw new MalformedURLException();
        cryptoAlgorithm = (short)(((extra[0] & 0xff) << 8) + (extra[1] & 0xff));
        if(cryptoAlgorithm != ALGO_AES_PCFB_256) {
            throw new MalformedURLException("Unrecognized crypto algorithm: "+cryptoAlgorithm);
        }
        hashCode = Fields.hashCode(routingKey) ^ Fields.hashCode(symCipherKey);
    }

    /**
     * @return The URI for this stream key.
     */
    public FreenetURI getURI() {
        return new FreenetURI("PSK", "", routingKey, symCipherKey, new byte[] { (byte)(cryptoAlgorithm>>8), (byte)cryptoAlgorithm });
    }

    public static ClientPublishStreamKey createRandom(RandomSource random) {
        byte[] routingKey = new byte[ROUTING_KEY_LENGTH];
        byte[] symCipherKey = new byte[SYM_CIPHER_KEY_LENGTH];
        random.nextBytes(routingKey);
        random.nextBytes(symCipherKey);
        return new ClientPublishStreamKey(routingKey, symCipherKey);
    }

    /**
     * @return The node-level key
     */
    public PublishStreamKey getKey() {
        return new PublishStreamKey(routingKey);
    }
    
    // FIXME: add a DSA key to sign messages
    // FIXME: maybe encrypt too?
    
    public boolean equals(Object o) {
        if(o == this) return true;
        if(o instanceof ClientPublishStreamKey) {
            ClientPublishStreamKey key = (ClientPublishStreamKey)o;
            return Arrays.equals(key.routingKey, routingKey) &&
            	Arrays.equals(key.symCipherKey, symCipherKey);
        } else return false;
    }
    
    public int hashCode() {
        return hashCode;
    }

    /**
     * Encrypt a packet to be sent out on the stream.
     * @param packetNumber
     * @param origData
     * @return
     */
    public byte[] encrypt(long packetNumber, byte[] data, Random random) {
        if(data.length > SIZE_MAX) throw new IllegalArgumentException();
        // Encrypt: IV(32) data(1024) datalength(2)
        byte[] buf = new byte[SYMMETRIC_KEYLENGTH + SIZE_MAX + 2];
        byte[] iv = new byte[SYMMETRIC_KEYLENGTH];
        random.nextBytes(iv);
        System.arraycopy(iv, 0, buf, 0, iv.length);
        System.arraycopy(data, 0, buf, SYMMETRIC_KEYLENGTH, data.length);
        if(data.length != SIZE_MAX) {
            MersenneTwister mt = new MersenneTwister(random.nextLong());
            byte[] temp = new byte[SIZE_MAX - data.length];
            mt.nextBytes(temp);
            System.arraycopy(temp, 0, buf, SYMMETRIC_KEYLENGTH + data.length, SIZE_MAX - data.length);
        }
        buf[SYMMETRIC_KEYLENGTH+SIZE_MAX] = (byte)(data.length >> 8);
        buf[SYMMETRIC_KEYLENGTH+SIZE_MAX+1] = (byte)(data.length);
        Logger.minor(this, "Length: "+data.length+": "+buf[SYMMETRIC_KEYLENGTH+SIZE_MAX]+","+buf[SYMMETRIC_KEYLENGTH+SIZE_MAX+1]);
        Logger.minor(this, "Plaintext : "+HexUtil.bytesToHex(buf));
        Rijndael r;
        try {
            r = new Rijndael(256,256);
        } catch (UnsupportedCipherException e) {
            throw new Error(e);
        }
        r.initialize(symCipherKey);
        PCFBMode pcfb = new PCFBMode(r);
        pcfb.blockEncipher(buf, 0, buf.length);
        Logger.minor(this, "Ciphertext: "+HexUtil.bytesToHex(buf));
        //Logger.minor(this, "Encrypting with "+HexUtil.bytesToHex(symCipherKey));
        return buf;
    }
    
    /**
     * Decrypt a packet from the stream.
     * @param packetNumber
     * @param packetData
     * @return
     */
    public byte[] decrypt(long packetNumber, byte[] packetData) {
        if(packetData.length != SIZE_MAX + SYMMETRIC_KEYLENGTH + 2) {
            Logger.error(this, "Stream packet wrong size!", new Exception("error"));
            return null;
        }
        Rijndael r;
        try {
            r = new Rijndael(256,256);
        } catch (UnsupportedCipherException e) {
            throw new Error(e);
        }
        r.initialize(symCipherKey);
        //Logger.minor(this, "Decrypting with "+HexUtil.bytesToHex(symCipherKey));
        PCFBMode pcfb = new PCFBMode(r);
        Logger.minor(this, "Ciphertext: "+HexUtil.bytesToHex(packetData));
        pcfb.blockDecipher(packetData, 0, packetData.length);
        Logger.minor(this, "Plaintext : "+HexUtil.bytesToHex(packetData));
        // Discard first SYMMETRIC_KEYLENGTH bytes (IV)
        int len1 = (packetData[SYMMETRIC_KEYLENGTH+SIZE_MAX] & 0xff);
        int len2 = (packetData[SYMMETRIC_KEYLENGTH+SIZE_MAX+1] & 0xff);
        int length = (len1 << 8) + len2;
        	
        if(length > SIZE_MAX) {
            Logger.error(this, "Could not decrypt packet: length="+length+" ("+len1+","+len2+")");
            return null;
        }
        
        byte[] output = new byte[length];
        System.arraycopy(packetData, SYMMETRIC_KEYLENGTH, output, 0, length);
        return output;
    }
}
