package freenet.crypt;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.Random;

import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.engines.AESLightEngine;
import org.bouncycastle.crypto.modes.AEADBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

/** Uses bouncycastle's AEAD code. BC provides Cipher*Stream but they don't work with 
 * authenticating. FIXME This probably needs an internal buffer. Shouldn't be too inefficient 
 * provided that any short writes are buffered before they reach here though.
 * @author toad
 */
public class AEADOutputStream extends FilterOutputStream {
    
    private final AEADBlockCipher cipher;
    
    /** Create an encrypting, authenticating OutputStream. Will write the nonce to the stream.
     * @param os The underlying OutputStream. 
     * @param key The encryption key.
     * @param nonce The nonce. This serves the function of an IV. As a nonce, this MUST be unique. 
     * We will write it to the stream so the other side can pick it up, like an IV. Should 
     * generally be generated from a SecureRandom. The top bit must be 0, i.e. nonce[0] &= 0x7F.
     * @param mainCipher The BlockCipher for encrypting data. E.g. AES; not a block mode. This will
     * be used for encrypting a fairly large amount of data so could be any of the 3 BC AES impl's.
     * @param hashCipher The BlockCipher for the final hash. E.g. AES, not a block mode. This will
     * not be used very much so should be e.g. an AESLightEngine. */
    public AEADOutputStream(OutputStream os, byte[] key, byte[] nonce, BlockCipher hashCipher,
            BlockCipher mainCipher) throws IOException {
        super(os);
        os.write(nonce);
        cipher = new OCBBlockCipher_v149(hashCipher, mainCipher);
        KeyParameter keyParam = new KeyParameter(key);
        AEADParameters params = new AEADParameters(keyParam, MAC_SIZE_BITS, nonce);
        cipher.init(true, params);
    }
    
    @Override
    public void write(int b) throws IOException {
        write(new byte[] { (byte)b });
    }
    
    @Override
    public void write(byte[] buf) throws IOException {
        write(buf, 0, buf.length);
    }
    
    @Override
    public void write(byte[] buf, int offset, int length) throws IOException {
        byte[] output = new byte[cipher.getUpdateOutputSize(length)];
        cipher.processBytes(buf, offset, length, output, 0);
        out.write(output);
    }
    
    @Override
    public void close() throws IOException {
        byte[] output = new byte[cipher.getOutputSize(0)];
        try {
            cipher.doFinal(output, 0);
        } catch (InvalidCipherTextException e) {
            // Impossible???
            throw new RuntimeException("Impossible: "+e);
        }
        out.write(output);
        out.close();
    }
    
    static final int MAC_SIZE_BITS = 128;
    static final int MAC_SIZE_BYTES = MAC_SIZE_BITS/8;
    static final int AES_BLOCK_SIZE = 16;
    public static final int AES_OVERHEAD = AES_BLOCK_SIZE + MAC_SIZE_BYTES;
    
    public static AEADOutputStream createAES(OutputStream os, byte[] key, SecureRandom random) throws IOException {
        return innerCreateAES(os, key, random);
    }
    
    /** For unit tests only */
    static AEADOutputStream innerCreateAES(OutputStream os, byte[] key, Random random) throws IOException {
        AESEngine mainCipher = new AESEngine();
        AESLightEngine hashCipher = new AESLightEngine();
        byte[] nonce = new byte[mainCipher.getBlockSize()];
        random.nextBytes(nonce);
        nonce[0] &= 0x7F;
        return new AEADOutputStream(os, key, nonce, hashCipher, mainCipher);
    }
    
    @Override
    public String toString() {
        return "AEADOutputStream:"+out.toString();
    }
    
}
