package freenet.keys;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

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
public class CHKBlock {

    final byte[] data;
    final byte[] header;
    final NodeCHK chk;
    protected static final int MAX_LENGTH_BEFORE_COMPRESSION = 1024 * 1024;
    
    public String toString() {
        return super.toString()+": chk="+chk;
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

    public CHKBlock(byte[] data2, byte[] header2, NodeCHK chk) throws CHKVerifyException {
        this(data2, header2, chk, true);
    }
    
    public CHKBlock(byte[] data2, byte[] header2, NodeCHK chk, boolean verify) throws CHKVerifyException {
        data = data2;
        header = header2;
        this.chk = chk;
        // FIXME: enable
        //if(!verify) return;
        
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
        byte[] check = chk.routingKey;
        if(!java.util.Arrays.equals(hash, check)) {
            throw new CHKVerifyException("Hash does not verify");
        }
        // Otherwise it checks out
    }

    /**
     * Decode the CHK and recover the original data
     * @return the original data
     */
    public byte[] decode(ClientCHK key) throws CHKDecodeException {
        // Overall hash already verified, so first job is to decrypt.
        System.err.println("Decoding "+key);
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
        if(key.compressed) {
            if(size < 4) throw new CHKDecodeException("No bytes to decompress");
            // Decompress
            // First get the length
            int len = ((((dbuf[0] & 0xff) << 8) + (dbuf[1] & 0xff)) << 8) +
            	(dbuf[2] & 0xff);
            System.err.println("Decompressed length: "+len);
            if(len > MAX_LENGTH_BEFORE_COMPRESSION)
                throw new CHKDecodeException("Invalid precompressed size: "+len);
            byte[] output = new byte[len];
            Inflater decompressor = new Inflater();
            decompressor.setInput(dbuf, 3, size-3);
            try {
                int resultLength = decompressor.inflate(output);
                if(resultLength != len)
                    throw new CHKDecodeException("Wanted "+len+" but got "+resultLength+" bytes from decompression");
            } catch (DataFormatException e2) {
                throw new CHKDecodeException(e2);
            }
            return output;
        }
        byte[] output = new byte[size];
        // No particular reason to check the padding, is there?
        System.arraycopy(dbuf, 0, output, 0, size);
        return output;
    }

    public Key getKey() {
        return chk;
    }
}
