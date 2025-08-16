package freenet.crypt;

import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.DataLengthException;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.params.KeyParameter;

class BlockCiphers {
    private static final boolean USE_JCE_FOR_AES = checkJceSupported("AES", 16, 24, 32);

    static BlockCipher aes() {
        try {
            return USE_JCE_FOR_AES ? new JceEcbBlockCipher("AES") : new AESEngine();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final class JceEcbBlockCipher implements BlockCipher {
        private final String algorithm;
        private final Cipher cipher;
        private final int blockSize;
        private final byte[] buf;

        JceEcbBlockCipher(String algorithm) throws NoSuchPaddingException, NoSuchAlgorithmException {
            this.algorithm = algorithm;
            this.cipher = Cipher.getInstance(algorithm + "/ECB/NoPadding");
            this.blockSize = cipher.getBlockSize();
            this.buf = new byte[blockSize];
        }

        @Override
        public void init(boolean forEncryption, CipherParameters params) throws IllegalArgumentException {
            Key key = new SecretKeySpec(((KeyParameter) params).getKey(), "AES");
            try {
                cipher.init(forEncryption ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, key);
            } catch (InvalidKeyException e) {
                throw new IllegalArgumentException(e);
            }
        }

        @Override
        public String getAlgorithmName() {
            return algorithm;
        }

        @Override
        public int getBlockSize() {
            return blockSize;
        }

        @Override
        public int processBlock(byte[] in, int inOff, byte[] out, int outOff) throws DataLengthException {
            try {
                // BouncyCastle's OCB implementation provides in == out.
                // Prevent JCE temp buffer allocation by passing buf as out, then copying the bytes.
                cipher.update(in, inOff, blockSize, buf, 0);
                System.arraycopy(buf, 0, out, outOff, blockSize);
                return blockSize;
            } catch (ShortBufferException e) {
                throw new DataLengthException(e.getMessage());
            }
        }

        @Override
        public void reset() {
            Arrays.fill(buf, (byte) 0);
        }
    }

    private static boolean checkJceSupported(String algorithm, int... keySizes) {
        try {
            for (int keySize : keySizes) {
                new JceEcbBlockCipher(algorithm).init(false, new KeyParameter(new byte[keySize]));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
