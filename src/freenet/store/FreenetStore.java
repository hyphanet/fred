package freenet.store;

import java.io.File;
import java.io.IOException;

import freenet.keys.CHKBlock;
import freenet.keys.CHKVerifyException;
import freenet.keys.NodeCHK;
import freenet.support.Fields;
import freenet.support.Logger;

/**
 * @author amphibian
 * 
 * Freenet datastore.
 */
public class FreenetStore {

    final DataStore dataStore;
    final DataStore headersStore;
    
    static final int DATA_BLOCK_SIZE = 32*1024;
    static final int HEADER_BLOCK_SIZE = 512;
    
    /**
     * @param storeFilename The name of the file containing the store.
     * @param headerStoreFilename The name of the file containing the headers store.
     * @param maxBlocks The maximum number of chunks stored in this store.
     */
    public FreenetStore(String storeFilename, String headerStoreFilename, long maxBlocks) throws Exception {
        dataStore = new DataStore(new File(storeFilename), new File(storeFilename+".index"), DATA_BLOCK_SIZE, maxBlocks);
        // FIXME: What's the right size? 512 is probably enough for SSKs?
        headersStore = new DataStore(new File(headerStoreFilename), new File(headerStoreFilename+".index"), HEADER_BLOCK_SIZE, maxBlocks);
    }

    /**
     * Retrieve a block.
     * @return null if there is no such block stored, otherwise the block.
     */
    public CHKBlock fetch(NodeCHK chk) throws IOException, CHKVerifyException {
        byte[] data = dataStore.getDataForBlock(chk);
        if(data == null) {
            if(headersStore.getDataForBlock(chk) != null) {
                Logger.normal(this, "Deleting: "+chk+" headers, no data");
                headersStore.delete(chk);
            }
            return null;
        }
        byte[] headers = headersStore.getDataForBlock(chk);
        if(headers == null) {
            // No headers, delete
            Logger.normal(this, "Deleting: "+chk+" data, no headers");
            dataStore.delete(chk);
        }
        // Decode
        int headerLen = ((headers[0] & 0xff) << 8) + (headers[1] & 0xff);
        if(headerLen > HEADER_BLOCK_SIZE-2 || headerLen < 0) {
            Logger.normal(this, "Invalid header data on "+chk+", deleting");
            dataStore.delete(chk);
            headersStore.delete(chk);
            return null;
        }
        byte[] buf = new byte[headerLen];
        System.arraycopy(headers, 2, buf, 0, headerLen);
//        Logger.minor(this, "Raw headers: "+headers.length+" bytes, hash "+Fields.hashCode(headers));
//        Logger.minor(this, "Headers: "+headerLen+" bytes, hash "+Fields.hashCode(headers));
//        Logger.minor(this, "Data: "+data.length+" bytes, hash "+Fields.hashCode(data));
        return new CHKBlock(data, buf, chk);
    }

    /**
     * Store a block.
     */
    public void put(CHKBlock block) throws IOException {
        byte[] data = block.getData();
        byte[] headers = block.getHeader();
        int hlen = headers.length;
        if(data.length != DATA_BLOCK_SIZE || hlen > HEADER_BLOCK_SIZE-2)
            throw new IllegalArgumentException("Too big - data: "+data.length+" should be "+
                    DATA_BLOCK_SIZE+", headers: "+hlen+" - should be "+(HEADER_BLOCK_SIZE-2));
        byte[] hbuf = new byte[HEADER_BLOCK_SIZE];
        hbuf[0] = (byte)(hlen >> 8);
        hbuf[1] = (byte)(hlen & 0xff);
        System.arraycopy(headers, 0, hbuf, 2, hlen);
//        Logger.minor(this, "Raw headers: "+hbuf.length+" bytes, hash "+Fields.hashCode(hbuf));
//        Logger.minor(this, "Headers: "+hlen+" bytes, hash "+Fields.hashCode(headers));
//        Logger.minor(this, "Data: "+data.length+" bytes, hash "+Fields.hashCode(data));
        dataStore.addDataAsBlock(block.getKey(), data);
        headersStore.addDataAsBlock(block.getKey(), hbuf);
    }
}
