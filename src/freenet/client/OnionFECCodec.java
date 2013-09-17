package freenet.client;

import com.onionnetworks.fec.PureCode;
import com.onionnetworks.util.Buffer;

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

    private PureCode getCodec(int k, int n) {
        // TODO Auto-generated method stub
        return null;
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
