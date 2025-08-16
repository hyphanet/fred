package freenet.crypt;

import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.security.SecureRandom;

import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.params.KeyParameter;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

public class BlockCiphersTest {
    private final BlockCipher selected = BlockCiphers.aes();

    @Before
    public void skipIfJceNotSupported() {
        Assume.assumeThat(selected, instanceOf(BlockCiphers.JceEcbBlockCipher.class));
    }

    @Test
    public void isCompatibleWithBouncyCastle() {
        BlockCipher bouncyCastle = new AESEngine();

        assertEquals(bouncyCastle.getBlockSize(), selected.getBlockSize());
        assertEquals(bouncyCastle.getAlgorithmName(), selected.getAlgorithmName());

        KeyParameter key = new KeyParameter(SecureRandom.getSeed(16));
        bouncyCastle.init(true, key);
        selected.init(true, key);

        byte[] block = SecureRandom.getSeed(24);
        byte[] expectedOut = new byte[block.length];
        byte[] actualOut = new byte[block.length];
        assertEquals(
                bouncyCastle.processBlock(block, 0, expectedOut, 0),
                selected.processBlock(block, 0, actualOut, 0)
        );
        assertArrayEquals(expectedOut, actualOut);
    }
}
