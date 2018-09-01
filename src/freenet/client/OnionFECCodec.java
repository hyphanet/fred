package freenet.client;

import java.lang.ref.SoftReference;

import com.onionnetworks.fec.PureCode;
import com.onionnetworks.util.Buffer;

import freenet.client.InsertContext.CompatibilityMode;
import freenet.support.LRUMap;

public class OnionFECCodec extends FECCodec {

    @Override
    public void decode(byte[][] dataBlocks, byte[][] checkBlocks, boolean[] dataBlocksPresent, boolean[] checkBlocksPresent, int blockLength) {
        int k = dataBlocks.length;
        int n = dataBlocks.length + checkBlocks.length;
        PureCode codec = getCodec(k, n);
        int[] blockNumbers = new int[k];
        Buffer[] buffers = new Buffer[k];
        // The data blocks are already in the correct positions in dataBlocks.
        for(int i=0;i<dataBlocks.length;i++) {
            if(dataBlocks[i].length != blockLength) throw new IllegalArgumentException();
            if(!dataBlocksPresent[i]) continue;
            buffers[i] = new Buffer(dataBlocks[i], 0, blockLength);
            blockNumbers[i] = i;
        }
        int target = 0;
        // Fill in the gaps with the check blocks.
        for(int i=0;i<checkBlocks.length;i++) {
            if(!checkBlocksPresent[i]) continue;
            if(checkBlocks[i].length != blockLength) throw new IllegalArgumentException();
            while(target < dataBlocks.length && buffers[target] != null) target++; // Scan for slot.
            if(target >= dataBlocks.length) continue;
            // Decode into the slot for the relevant data block.
            buffers[target] = new Buffer(dataBlocks[target]);
            // Provide the data from the check block.
            blockNumbers[target] = i + dataBlocks.length;
            System.arraycopy(checkBlocks[i], 0, dataBlocks[target], 0, blockLength);
        }
        
        // Now do the decode.
        codec.decode(buffers, blockNumbers);
        // The data blocks are now decoded and in the correct locations.
    }

    /** Cache of PureCode by {k,n}. The memory usage is relatively small so we account for it in 
     * the FEC jobs, see maxMemoryOverheadDecode() etc. */
    private synchronized static PureCode getCodec(int k, int n) {
        CodecKey key = new CodecKey(k, n);
        SoftReference<PureCode> codeRef;
        while((codeRef = recentlyUsedCodecs.peekValue()) != null) {
            // Remove oldest codecs if they have been GC'ed.
            if(codeRef.get() == null) {
                recentlyUsedCodecs.popKey();
            } else {
                break;
            }
        }
        codeRef = recentlyUsedCodecs.get(key);
        if(codeRef != null) {
            PureCode code = codeRef.get();
            if(code != null) {
                recentlyUsedCodecs.push(key, codeRef);
                return code;
            }
        }
        PureCode code = new PureCode(k, n);
        recentlyUsedCodecs.push(key, new SoftReference<PureCode>(code));
        return code;
    }
    
    private static final LRUMap<CodecKey, SoftReference<PureCode>> recentlyUsedCodecs = LRUMap.createSafeMap();

    private static class CodecKey implements Comparable<CodecKey> {
        /** Number of input blocks */
        int k;
        /** Number of output blocks, including input blocks */
        int n;

        public CodecKey(int k, int n) {
            this.n = n;
            this.k = k;
        }

        @Override
        public boolean equals(Object o) {
            if(o instanceof CodecKey) {
                CodecKey key = (CodecKey)o;
                return (key.n == n) && (key.k == k);
            } else {
                return false;
            }
        }

        @Override
        public int hashCode() {
            return (n << 16) + k;
        }

        @Override
        public int compareTo(CodecKey o) {
            if(n > o.n) return 1;
            if(n < o.n) return -1;
            if(k > o.k) return 1;
            if(k < o.k) return -1;
            return 0;
        }
    }



    @Override
    public void encode(byte[][] dataBlocks, byte[][] checkBlocks, boolean[] checkBlocksPresent, 
            int blockLength) {
        int k = dataBlocks.length;
        int n = dataBlocks.length + checkBlocks.length;
        PureCode codec = getCodec(k, n);
        Buffer[] data = new Buffer[dataBlocks.length];
        for(int i=0;i<data.length;i++) {
            if(dataBlocks[i] == null || dataBlocks[i].length != blockLength)
                throw new IllegalArgumentException();
            data[i] = new Buffer(dataBlocks[i]);
        }
        int mustEncode = 0;
        for(int i=0;i<checkBlocks.length;i++) {
            if(checkBlocks[i] == null || checkBlocks[i].length != blockLength)
                throw new IllegalArgumentException();
            if(!checkBlocksPresent[i]) mustEncode++;
        }
        Buffer[] check = new Buffer[mustEncode];
        if(mustEncode == 0) return; // Done already.
        int[] toEncode = new int[mustEncode];
        int x = 0;
        for(int i=0;i<checkBlocks.length;i++) {
            if(checkBlocksPresent[i]) continue;
            check[x] = new Buffer(checkBlocks[i]);
            toEncode[x++] = i+dataBlocks.length;
        }
        codec.encode(data, check, toEncode);
    }

    @Override
    public long maxMemoryOverheadDecode(int dataBlocks, int checkBlocks) {
        int n = dataBlocks + checkBlocks;
        int k = dataBlocks;
        int matrixSize = n*k*2; // char[] of n*k
        return matrixSize*3; // Very approximately, the last one absorbing some columns and fixed overhead.
    }

    @Override
    public long maxMemoryOverheadEncode(int dataBlocks, int checkBlocks) {
        int n = dataBlocks + checkBlocks;
        int k = dataBlocks;
        int matrixSize = n*k*2; // char[] of n*k
        return matrixSize*3; // Very approximately, the last one absorbing some columns and fixed overhead.
    }
    
    @Override
    public int getCheckBlocks(int dataBlocks, CompatibilityMode compatibilityMode) {
        /**
         * ALCHEMY: What we do know is that redundancy by FEC is much more efficient than 
         * redundancy by simply duplicating blocks, for obvious reasons (see e.g. Wuala). But
         * we have to have some redundancy at the duplicating blocks level because we do use
         * some keys directly etc: we store an insert in 3 nodes. We also cache it on 20 nodes,
         * but generally the key will fall out of the caches within days. So long term, it's 3.
         * Multiplied by 2 here, makes 6. Used to be 1.5 * 3 = 4.5. Wuala uses 5, but that's 
         * all FEC.
         */
        int checkBlocks = dataBlocks * HighLevelSimpleClientImpl.SPLITFILE_CHECK_BLOCKS_PER_SEGMENT / HighLevelSimpleClientImpl.SPLITFILE_SCALING_BLOCKS_PER_SEGMENT;
        if(dataBlocks >= HighLevelSimpleClientImpl.SPLITFILE_CHECK_BLOCKS_PER_SEGMENT) 
            checkBlocks = HighLevelSimpleClientImpl.SPLITFILE_CHECK_BLOCKS_PER_SEGMENT;
        // An extra block for anything below the limit.
        checkBlocks++;
        // Keep it within 256 blocks.
        if(dataBlocks < 256 && dataBlocks + checkBlocks > 256)
            checkBlocks = 256 - dataBlocks;
        if(compatibilityMode == InsertContext.CompatibilityMode.COMPAT_1250 || compatibilityMode == InsertContext.CompatibilityMode.COMPAT_1250_EXACT) {
            // Pre-1250, redundancy was always 100% or less.
            // Builds of that period using the native FEC (ext #26) will segfault sometimes on >100% redundancy.
            // So limit check blocks to data blocks.
            if(checkBlocks > dataBlocks) checkBlocks = dataBlocks;
        }
        return checkBlocks;
    }

}
