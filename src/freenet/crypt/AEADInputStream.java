package freenet.crypt;

import java.io.DataInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.engines.AESLightEngine;
import org.bouncycastle.crypto.modes.AEADBlockCipher;
import org.bouncycastle.crypto.modes.OCBBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

public class AEADInputStream extends FilterInputStream {
    
    private static final int MAC_SIZE = AEADOutputStream.MAC_SIZE;
    private final AEADBlockCipher cipher;
    
    /** Create a decrypting, authenticating InputStream. IMPORTANT: We only authenticate when 
     * closing the stream, so do NOT use Closer.close() etc and swallow IOException's on close(),
     * as that's what we will throw if authentication fails. We will read the nonce from the 
     * stream; it functions similarly to an IV.
     * @param is The underlying InputStream. 
     * @param key The encryption key.
     * @param nonce The nonce. This serves the function of an IV. As a nonce, this MUST be unique. 
     * We will write it to the stream so the other side can pick it up, like an IV. 
     * @param mainCipher The BlockCipher for encrypting data. E.g. AES; not a block mode. This will
     * be used for encrypting a fairly large amount of data so could be any of the 3 BC AES impl's.
     * @param hashCipher The BlockCipher for the final hash. E.g. AES, not a block mode. This will
     * not be used very much so should be e.g. an AESLightEngine. */
    public AEADInputStream(InputStream is, byte[] key, BlockCipher hashCipher, 
            BlockCipher mainCipher) throws IOException {
        super(is);
        byte[] nonce = new byte[mainCipher.getBlockSize()];
        new DataInputStream(is).readFully(nonce);
        cipher = new OCBBlockCipher(hashCipher, mainCipher);
        KeyParameter keyParam = new KeyParameter(key);
        AEADParameters params = new AEADParameters(keyParam, MAC_SIZE, nonce);
        cipher.init(false, params);
    }
    
    public final int getIVSize() {
        return cipher.getUnderlyingCipher().getBlockSize() / 8;
    }
    
    private final byte[] onebyte = new byte[1];
    
    public int read() throws IOException {
        int length = read(onebyte);
        if(length <= 0) return -1;
        else return onebyte[0];
    }
    
    public int read(byte[] buf) throws IOException {
        return read(buf, 0, buf.length);
    }
    
    public int read(byte[] buf, int offset, int length) throws IOException {
        // FIXME no idea whether it's safe to use in=out here.
        byte[] temp = new byte[length];
        int read = in.read(temp);
        if(read <= 0) return read;
        return cipher.processBytes(temp, 0, read, buf, offset);
    }
    
    public void close() throws IOException {
        byte[] tag = new byte[cipher.getOutputSize(0)];
        new DataInputStream(in).readFully(tag);
        try {
            cipher.doFinal(tag, 0);
        } catch (InvalidCipherTextException e) {
            throw new AEADVerificationFailedException();
        }
        in.close();
    }
    
    public static AEADInputStream createAES(InputStream is, byte[] key) throws IOException {
        AESEngine mainCipher = new AESEngine();
        AESLightEngine hashCipher = new AESLightEngine();
        return new AEADInputStream(is, key, hashCipher, mainCipher);
    }

}
