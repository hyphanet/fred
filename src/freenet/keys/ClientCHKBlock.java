package freenet.keys;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Random;

import freenet.crypt.BlockCipher;
import freenet.crypt.PCFBMode;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;

/**
 * @author amphibian
 * 
 * Client CHK, plus the actual data. Used for encoding a block of data into
 * a CHK, or decoding a fetched block into something usable by the client.
 * Can provide the client URI, not just the node level routing key.
 */
public class ClientCHKBlock {

    ClientCHK key;
    byte[] data;
    byte[] header;
    
    static public ClientCHKBlock encode(byte[] sourceData) throws CHKEncodeException {
        return new ClientCHKBlock(sourceData);
    }
    
    /**
     * Encode a block of data to a ClientCHKBlock.
     */
    ClientCHKBlock(byte[] sourceData) throws CHKEncodeException {
        MessageDigest md160;
        try {
            md160 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e2) {
            // FIXME: log properly? Not much we can do... But we could log a
            // more user-friendly message...
            throw new Error(e2);
        }
        // FIXME: gzip support
        if(sourceData.length > 32768) {
            throw new CHKEncodeException("Too big");
        }
        // First pad it
        if(sourceData.length < 32768) {
            // Hash the data
            md160.digest(new byte[] { 0 });
            if(sourceData.length > 0)
            	md160.update(sourceData);
            byte[] digest = md160.digest();
            // FIXME: use Mersenne Twistors
            long seed;
            seed = (digest[0] & 0xff);
            seed = seed << 8 + (digest[1] & 0xff);
            seed = seed << 8 + (digest[2] & 0xff);
            seed = seed << 8 + (digest[3] & 0xff);
            seed = seed << 8 + (digest[4] & 0xff);
            seed = seed << 8 + (digest[5] & 0xff);
            seed = seed << 8 + (digest[6] & 0xff);
            seed = seed << 8 + (digest[7] & 0xff);
            Random r = new Random(seed);
            data = new byte[32768];
            System.arraycopy(sourceData, 0, data, 0, sourceData.length);
            byte[] randomBytes = new byte[32768-sourceData.length];
            r.nextBytes(randomBytes);
            System.arraycopy(randomBytes, 0, data, sourceData.length, 32768-sourceData.length);
        } else {
        	data = sourceData;
        }
        // Now make the header
        MessageDigest md256;
        try {
            md256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e1) {
            // FIXME: log this properly?
            throw new Error(e1);
        }
        byte[] encKey = md256.digest(data);
        md256.reset();
        // IV = E(H(crypto key))
        byte[] plainIV = md256.digest(encKey);
        header = new byte[plainIV.length+2];
        System.arraycopy(plainIV, 0, header, 0, plainIV.length);
        header[plainIV.length] = (byte)(sourceData.length >> 8);
        header[plainIV.length+1] = (byte)(sourceData.length & 0xff);
        // GRRR, java 1.4 does not have any symmetric crypto
        // despite exposing asymmetric and hashes!
        
        // Now encrypt the header, then the data, using the same PCFB instance
        BlockCipher cipher;
        try {
            cipher = new Rijndael(256);
        } catch (UnsupportedCipherException e) {
            // FIXME - log this properly
            throw new Error(e);
        }
        cipher.initialize(encKey);
        PCFBMode pcfb = new PCFBMode(cipher);
        pcfb.blockEncipher(header, 0, header.length);
        pcfb.blockEncipher(data, 0, data.length);
        
        // Now calculate the final hash
        md160.update(header);
        byte[] finalHash = md160.digest(data);
        
        // Now convert it into a ClientCHK
        key = new ClientCHK(finalHash, encKey, false, false, ClientCHK.ALGO_AES_PCFB_256);
    }

    /**
     * @return The ClientCHK for this key.
     */
    public ClientCHK getKey() {
        return key;
    }

    /**
     * @return The header for this key. DO NOT MODIFY THIS DATA!
     */
    public byte[] getHeader() {
        return header;
    }

    /**
     * @return The actual data for this key. DO NOT MODIFY THIS DATA!
     */
    public byte[] getData() {
        return data;
    }

    /**
     * Construct from data retrieved, and a key.
     * Do not do full decode. Verify what can be verified without doing
     * a full decode.
     * @param k The client key.
     * @param header2 The header.
     * @param data2 The data.
     */
    public ClientCHKBlock(ClientCHK k, byte[] header2, byte[] data2) throws CHKVerifyException {
        key = k;
        header = header2;
        data = data2;
        // Minimal verification
        // Check the hash
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new Error(e);
        }
        
        md.update(header);
        md.update(data);
        byte[] hash = md.digest();
        byte[] check = k.routingKey;
        if(!java.util.Arrays.equals(hash, check)) {
            throw new CHKVerifyException("Hash does not verify");
        }
        // Otherwise it checks out
    }
    
    /**
     * Decode the CHK and recover the original data
     * @return the original data
     */
    public byte[] decode() throws CHKDecodeException {
        // Overall hash already verified, so first job is to decrypt.
        if(key.cryptoAlgorithm != ClientCHK.ALGO_AES_PCFB_256)
            throw new UnsupportedOperationException();
        BlockCipher cipher;
        try {
            cipher = new Rijndael(256);
        } catch (UnsupportedCipherException e) {
            // FIXME - log this properly
            throw new Error(e);
        }
        cipher.initialize(key.cryptoKey);
        PCFBMode pcfb = new PCFBMode(cipher);
        byte[] hbuf = new byte[header.length];
        System.arraycopy(header, 0, hbuf, 0, header.length);
        byte[] dbuf = new byte[data.length];
        System.arraycopy(data, 0, dbuf, 0, data.length);
        pcfb.blockDecipher(hbuf, 0, hbuf.length);
        pcfb.blockDecipher(dbuf, 0, dbuf.length);
        // Check: Decryption key == hash of data (not including header)
        MessageDigest md256;
        try {
            md256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e1) {
            // FIXME: log this properly?
            throw new Error(e1);
        }
        byte[] dkey = md256.digest(dbuf);
        if(!java.util.Arrays.equals(dkey, key.cryptoKey)) {
            throw new CHKDecodeException("Check failed: decrypt key == H(data)");
        }
        // Check: IV == hash of decryption key
        byte[] predIV = md256.digest(dkey);
        // Extract the IV
        byte[] iv = new byte[32];
        System.arraycopy(hbuf, 0, iv, 0, 32);
        if(!Arrays.equals(iv, predIV))
            throw new CHKDecodeException("Check failed: Decrypted IV == H(decryption key)");
        // Checks complete
        int size = ((hbuf[32] & 0xff) << 8) + (hbuf[33] & 0xff);
        if(size > 32768 || size < 0)
            throw new CHKDecodeException("Invalid size: "+size);
        byte[] output = new byte[size];
        // No particular reason to check the padding, is there?
        System.arraycopy(dbuf, 0, output, 0, size);
        return output;
    }
}
