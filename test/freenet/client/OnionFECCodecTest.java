package freenet.client;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Random;

import org.junit.Before;
import org.junit.Test;

import freenet.support.TestProperty;

/**
 * Test the new (post db4o) high level FEC API
 */
public class OnionFECCodecTest {

    private static final int BLOCK_SIZE = 4096;

    private final OnionFECCodec codec = new OnionFECCodec();
    private byte[][] originalDataBlocks;
    private byte[][] dataBlocks;
    private byte[][] originalCheckBlocks;
    private byte[][] checkBlocks;
    private boolean[] checkBlocksPresent;
    private boolean[] dataBlocksPresent;
    private Random random;

    @Before
    public void setUp() throws Exception {
        random = new Random(21482106);
    }

    @Test
    public void testDecodeRandomSubset() {
        int iterations = TestProperty.EXTENSIVE ? 100 : 10;
        for (int i = 0; i < iterations; i++) {
            inner(128, 128, random);
        }
        for (int i = 0; i < iterations; i++) {
            inner(127, 129, random);
        }
        for (int i = 0; i < iterations; i++) {
            inner(129, 127, random);
        }
    }

    @Test
    public void testEncodeThrowsOnNotPaddedLastBlock() {
        int data = 128;
        int check = 128;
        originalDataBlocks = createOriginalDataBlocks(random, data);
        originalDataBlocks[data - 1] = new byte[BLOCK_SIZE / 2];
        checkBlocks = setupCheckBlocks(check);
        dataBlocks = copy(originalDataBlocks);

        // Encode the check blocks.
        checkBlocksPresent = new boolean[checkBlocks.length];
        assertThrows(
            IllegalArgumentException.class,
            () -> codec.encode(dataBlocks, checkBlocks, checkBlocksPresent, BLOCK_SIZE)
        );
    }

    @Test
    public void testDecodeThrowsOnNotPaddedLastBlock() {
        setup(128, 128, random);
        // Now delete a random selection of blocks
        deleteRandomBlocks(random);
        dataBlocks[127] = new byte[BLOCK_SIZE / 2];
        assertThrows(
            IllegalArgumentException.class,
            () -> codec.encode(dataBlocks, checkBlocks, checkBlocksPresent, BLOCK_SIZE)
        );
    }

    @Test
    public void testDecodeAlreadyDecoded() {
        setup(128, 128, random);
        // Now delete a random selection of blocks
        deleteAllCheckBlocks();
        decode(); // Should be a no-op.
    }

    @Test
    public void testDecodeNoneDecoded() {
        setup(128, 128, random);
        // Now delete a random selection of blocks
        deleteAllDataBlocks();
        decode();
    }

    @Test
    public void testManyCheckFewData() {
        inner(2, 253, random);
        inner(5, 250, random);
        inner(50, 200, random);
        inner(2, 3, random); // Common case, include it here.
    }

    @Test
    public void testManyDataFewCheck() {
        inner(200, 55, random);
        inner(253, 2, random);
    }

    @Test
    public void testRandomDataCheckCounts() {
        int iterations = TestProperty.EXTENSIVE ? 100 : 10;
        for (int i = 0; i < iterations; i++) {
            int data = random.nextInt(252) + 2;
            int maxCheck = 255 - data;
            int check = random.nextInt(maxCheck) + 1;
            inner(data, check, random);
        }
    }

    protected void inner(int data, int check, Random r) {
        setup(data, check, r);
        // Now delete a random selection of blocks
        deleteRandomBlocks(r);
        decode();
    }

    protected void setup(int data, int check, Random r) {
        originalDataBlocks = createOriginalDataBlocks(r, data);
        checkBlocks = setupCheckBlocks(check);
        dataBlocks = copy(originalDataBlocks);

        // Encode the check blocks.
        checkBlocksPresent = new boolean[checkBlocks.length];
        codec.encode(dataBlocks, checkBlocks, checkBlocksPresent, BLOCK_SIZE);
        assertBlockArrayEquals(originalDataBlocks, dataBlocks);
        originalCheckBlocks = copy(checkBlocks);

        // Initially everything is present...
        dataBlocksPresent = new boolean[dataBlocks.length];
        Arrays.fill(dataBlocksPresent, true);
        Arrays.fill(checkBlocksPresent, true);
    }

    protected void decode() {
        boolean[] oldDataBlocksPresent = dataBlocksPresent.clone();
        boolean[] oldCheckBlocksPresent = checkBlocksPresent.clone();
        codec.decode(dataBlocks, checkBlocks, dataBlocksPresent, checkBlocksPresent, BLOCK_SIZE);
        assertBlockArrayEquals(originalDataBlocks, dataBlocks);
        assertArrayEquals(oldDataBlocksPresent, dataBlocksPresent);
        assertArrayEquals(oldCheckBlocksPresent, checkBlocksPresent);
        Arrays.fill(dataBlocksPresent, true);
        codec.encode(dataBlocks, checkBlocks, checkBlocksPresent, BLOCK_SIZE);
        assertBlockArrayEquals(originalCheckBlocks, checkBlocks);
        assertArrayEquals(oldCheckBlocksPresent, checkBlocksPresent);
    }

    private void deleteRandomBlocks(Random r) {
        int dropped = 0;
        int data = dataBlocks.length;
        int check = checkBlocks.length;
        while (dropped < check) {
            int blockNo = r.nextInt(data + check);
            if (blockNo < data) {
                if (!dataBlocksPresent[blockNo]) {
                    continue;
                }
                clear(dataBlocks, blockNo);
                dataBlocksPresent[blockNo] = false;
            } else {
                blockNo -= data;
                if (!checkBlocksPresent[blockNo]) {
                    continue;
                }
                clear(checkBlocks, blockNo);
                checkBlocksPresent[blockNo] = false;
            }
            dropped++;
        }
    }

    private void clear(byte[][] dataBlocks, int blockNo) {
        Arrays.fill(dataBlocks[blockNo], (byte) 0);
    }

    protected byte[][] createOriginalDataBlocks(Random r, int count) {
        byte[][] blocks = new byte[count][];
        for (int i = 0; i < count; i++) {
            blocks[i] = new byte[BLOCK_SIZE];
            r.nextBytes(blocks[i]);
        }
        return blocks;
    }

    protected byte[][] setupCheckBlocks(int count) {
        byte[][] blocks = new byte[count][];
        for (int i = 0; i < count; i++) {
            blocks[i] = new byte[BLOCK_SIZE];
        }
        return blocks;
    }

    protected byte[][] copy(byte[][] blocks) {
        byte[][] ret = new byte[blocks.length][];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = Arrays.copyOf(blocks[i], blocks[i].length);
        }
        return ret;
    }

    private void assertBlockArrayEquals(byte[][] blocks1, byte[][] blocks2) {
        assertEquals(blocks1.length, blocks2.length);
        for (int i = 0; i < blocks1.length; i++) {
            assertArrayEquals(blocks1[i], blocks2[i]);
        }
    }

    private void deleteAllDataBlocks() {
        for (int i = 0; i < dataBlocks.length; i++) {
            clear(dataBlocks, i);
            dataBlocksPresent[i] = false;
        }
    }

    private void deleteAllCheckBlocks() {
        for (int i = 0; i < checkBlocks.length; i++) {
            clear(checkBlocks, i);
            checkBlocksPresent[i] = false;
        }
    }

}
