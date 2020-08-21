package freenet.client;

import java.util.Arrays;
import java.util.Random;

import freenet.support.TestProperty;

import junit.framework.TestCase;

/** Test the new (post db4o) high level FEC API */
public class OnionFECCodecTest extends TestCase {
    
    private static final int BLOCK_SIZE = 4096;
    private static final int MAX_SEGMENT_SIZE = 255;
    
    private final OnionFECCodec codec = new OnionFECCodec();
    private byte[][] originalDataBlocks;
    private byte[][] dataBlocks;
    private byte[][] originalCheckBlocks;
    private byte[][] checkBlocks;
    private boolean[] checkBlocksPresent;
    private boolean[] dataBlocksPresent;
    
    public void testDecodeRandomSubset() {
        Random r = new Random(19412106);
        int iterations = TestProperty.EXTENSIVE ? 100 : 10;
        for(int i=0;i<iterations;i++)
            inner(128, 128, r);
        for(int i=0;i<iterations;i++)
            inner(127, 129, r);
        for(int i=0;i<iterations;i++)
            inner(129, 127, r);
    }
    
    public void testEncodeThrowsOnNotPaddedLastBlock() {
        Random r = new Random(21502106);
        int data = 128;
        int check = 128;
        originalDataBlocks = createOriginalDataBlocks(r, data);
        originalDataBlocks[data-1] = new byte[BLOCK_SIZE/2];
        checkBlocks = setupCheckBlocks(check);
        dataBlocks = copy(originalDataBlocks);
        
        // Encode the check blocks.
        checkBlocksPresent = new boolean[checkBlocks.length];
        try {
            codec.encode(dataBlocks, checkBlocks, checkBlocksPresent, BLOCK_SIZE);
            assertTrue(false); // Should throw here!
        } catch (IllegalArgumentException e) {
            // Expected.
        }
    }
    
    public void testDecodeThrowsOnNotPaddedLastBlock() {
        Random r = new Random(21482106);
        setup(128, 128, r);
        // Now delete a random selection of blocks
        deleteRandomBlocks(r);
        dataBlocks[127] = new byte[BLOCK_SIZE/2];
        try {
            codec.decode(dataBlocks, checkBlocks, dataBlocksPresent, checkBlocksPresent, BLOCK_SIZE);
            assertTrue(false); // Should throw
        } catch (IllegalArgumentException e) {
            // Ok.
        }
    }
    
    public void testDecodeAlreadyDecoded() {
        Random r = new Random(21482106);
        setup(128, 128, r);
        // Now delete a random selection of blocks
        deleteAllCheckBlocks();
        decode(); // Should be a no-op.
    }
    
    public void testDecodeNoneDecoded() {
        Random r = new Random(21482106);
        setup(128, 128, r);
        // Now delete a random selection of blocks
        deleteAllDataBlocks();
        decode();
    }
    
    public void testManyCheckFewData() {
        Random r = new Random(21582106);
        inner(2, 253, r);
        inner(5, 250, r);
        inner(50, 200, r);
        inner(2, 3, r); // Common case, include it here.
    }
    
    public void testManyDataFewCheck() {
        Random r = new Random(21592106);
        inner(200, 55, r);
        inner(253, 2, r);
    }
    
    public void testRandomDataCheckCounts() {
        Random r = new Random(21602106);
        int iterations = TestProperty.EXTENSIVE ? 100 : 10;
        for(int i=0;i<iterations;i++) {
            int data = r.nextInt(252)+2;
            int maxCheck = 255 - data;
            int check = r.nextInt(maxCheck)+1;
            inner(data, check, r);
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
        assertEquals(originalDataBlocks, dataBlocks);
        originalCheckBlocks = copy(checkBlocks);
        
        // Initially everything is present...
        dataBlocksPresent = new boolean[dataBlocks.length];
        for(int i=0;i<dataBlocksPresent.length;i++) dataBlocksPresent[i] = true;
        for(int i=0;i<checkBlocksPresent.length;i++) checkBlocksPresent[i] = true;
    }
    
    protected void decode() {
        boolean[] oldDataBlocksPresent = dataBlocksPresent.clone();
        boolean[] oldCheckBlocksPresent = checkBlocksPresent.clone();
        codec.decode(dataBlocks, checkBlocks, dataBlocksPresent, checkBlocksPresent, BLOCK_SIZE);
        assertEquals(originalDataBlocks, dataBlocks);
        assertTrue(Arrays.equals(oldDataBlocksPresent, dataBlocksPresent));
        assertTrue(Arrays.equals(oldCheckBlocksPresent, checkBlocksPresent));
        for(int i=0;i<dataBlocksPresent.length;i++) dataBlocksPresent[i] = true;
        codec.encode(dataBlocks, checkBlocks, checkBlocksPresent, BLOCK_SIZE);
        assertEquals(originalCheckBlocks, checkBlocks);
        assertTrue(Arrays.equals(oldCheckBlocksPresent, checkBlocksPresent));
    }
    
    private void deleteRandomBlocks(Random r) {
        int dropped = 0;
        int data = dataBlocks.length;
        int check = checkBlocks.length;
        while(dropped < check) {
            int blockNo = r.nextInt(data+check);
            if(blockNo < data) {
                if(!dataBlocksPresent[blockNo]) continue;
                clear(dataBlocks, blockNo);
                dataBlocksPresent[blockNo] = false;
            } else {
                blockNo -= data;
                if(!checkBlocksPresent[blockNo]) continue;
                clear(checkBlocks, blockNo);
                checkBlocksPresent[blockNo] = false;
            }
            dropped++;
        }
    }

    private void clear(byte[][] dataBlocks, int blockNo) {
        Arrays.fill(dataBlocks[blockNo], (byte)0);
   }

    protected byte[][] createOriginalDataBlocks(Random r, int count) {
        byte[][] blocks = new byte[count][];
        for(int i=0;i<count;i++) {
            blocks[i] = new byte[BLOCK_SIZE];
            r.nextBytes(blocks[i]);
        }
        return blocks;
    }
    
    protected byte[][] setupCheckBlocks(int count) {
        byte[][] blocks = new byte[count][];
        for(int i=0;i<count;i++) {
            blocks[i] = new byte[BLOCK_SIZE];
        }
        return blocks;
    }
    
    protected byte[][] copy(byte[][] blocks) {
        byte[][] ret = new byte[blocks.length][]; // FIXME would blocks.clone() shallow or deep copy?
        for(int i=0;i<ret.length;i++) {
            ret[i] = blocks[i].clone();
        }
        return ret;
    }
    
    private void assertEquals(byte[][] blocks1, byte[][] blocks2) {
        assertEquals(blocks1.length, blocks2.length);
        for(int i=0;i<blocks1.length;i++) {
            assertTrue(Arrays.equals(blocks1[i], blocks2[i]));
        }
    }
    
    private void deleteAllDataBlocks() {
        for(int i=0;i<dataBlocks.length;i++) {
            clear(dataBlocks, i);
            dataBlocksPresent[i] = false;
        }
    }

    private void deleteAllCheckBlocks() {
        for(int i=0;i<checkBlocks.length;i++) {
            clear(checkBlocks, i);
            checkBlocksPresent[i] = false;
        }
    }

}
