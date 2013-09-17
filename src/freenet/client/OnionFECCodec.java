package freenet.client;

import java.lang.ref.SoftReference;

import com.onionnetworks.fec.PureCode;
import com.onionnetworks.util.Buffer;

import freenet.support.LRUMap;

public class OnionFECCodec extends NewFECCodec {

    @Override
    public void decode(byte[][] dataBlocks, byte[][] checkBlocks, boolean[] dataBlocksPresent, boolean[] checkBlocksPresent, int blockLength) {
        int k = dataBlocks.length;
        int n = dataBlocks.length + checkBlocks.length;
        PureCode codec = getCodec(k, n);
        int[] blockNumbers = new int[k];
        Buffer[] buffers = new Buffer[k];
        int target = 0;
        // The data blocks are already in the correct positions in dataBlocks.
        for(int i=0;i<dataBlocks.length;i++) {
            buffers[i] = new Buffer(dataBlocks[i], 0, blockLength);
            if(!dataBlocksPresent[i]) continue;
            blockNumbers[i] = i;
            if(dataBlocks[i].length != blockLength) throw new IllegalArgumentException();
        }
        // Fill in the gaps with the check blocks.
        for(int i=0;i<checkBlocks.length;i++) {
            if(!checkBlocksPresent[i]) continue;
            if(checkBlocks[i].length != blockLength) throw new IllegalArgumentException();
            while(dataBlocksPresent[target]) {
                if(target > dataBlocks.length) break;
            }
            System.arraycopy(checkBlocks[i], 0, dataBlocks[target], 0, blockLength);
        }
        
        // Now do the decode.
        codec.decode(buffers, blockNumbers);
        // The data blocks are now decoded and in the correct locations.
    }

    /** Cache of PureCode by {k,n}. The memory usage is relatively small so we account for it in 
     * the FEC jobs, see maxMemoryOverheadDecode() etc. */
    private synchronized static PureCode getCodec(int k, int n) {
        MyKey key = new MyKey(k, n);
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
    
    private static final LRUMap<MyKey, SoftReference<PureCode>> recentlyUsedCodecs = LRUMap.createSafeMap();

    private static class MyKey implements Comparable<MyKey> {
        /** Number of input blocks */
        int k;
        /** Number of output blocks, including input blocks */
        int n;

        public MyKey(int k, int n) {
            this.n = n;
            this.k = k;
        }

        @Override
        public boolean equals(Object o) {
            if(o instanceof MyKey) {
                MyKey key = (MyKey)o;
                return (key.n == n) && (key.k == k);
            } else return false;
        }

        @Override
        public int hashCode() {
            return (n << 16) + k;
        }

        @Override
        public int compareTo(MyKey o) {
            if(n > o.n) return 1;
            if(n < o.n) return -1;
            if(k > o.k) return 1;
            if(k < o.k) return -1;
            return 0;
        }
    }



    @Override
    public void encode(byte[][] dataBlocks, byte[][] checkBlocks) {
        PureCode codec = getCodec(dataBlocks.length, checkBlocks.length);
        // TODO Auto-generated method stub

    }

    @Override
    public long maxMemoryOverheadDecode(int dataBlocks, int checkBlocks) {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public long maxMemoryOverheadEncode(int dataBlocks, int checkBlocks) {
        // TODO Auto-generated method stub
        return 0;
    }

}
