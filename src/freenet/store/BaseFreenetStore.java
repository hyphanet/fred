package freenet.store;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

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
public class BaseFreenetStore implements FreenetStore {

    final DataStore dataStore;
    final DataStore headersStore;
    
    static final int DATA_BLOCK_SIZE = 32*1024;
    static final int HEADER_BLOCK_SIZE = 512;
    
    public BaseFreenetStore(String filename, long maxBlocks) throws FileNotFoundException, Exception {
        this(new RandomAccessFile(filename+"-store","rw"), new RandomAccessFile(filename+"-storeindex","rw"),
                new RandomAccessFile(filename+"-header","rw"), new RandomAccessFile(filename+"-headerindex","rw"), maxBlocks);
    }
    
    public BaseFreenetStore(RandomAccessFile storeFile, RandomAccessFile storeIndexFile, 
            RandomAccessFile headerStoreFile, RandomAccessFile headerStoreIndexFile, long maxBlocks) throws Exception {
        dataStore = new DataStore(storeIndexFile, storeFile, DATA_BLOCK_SIZE, maxBlocks);
        headersStore = new DataStore(headerStoreIndexFile, headerStoreFile, HEADER_BLOCK_SIZE, maxBlocks);
    }
    
    /**
     * @param storeFilename The name of the file containing the store.
     * @param headerStoreFilename The name of the file containing the headers store.
     * @param maxBlocks The maximum number of chunks stored in this store.
     */
    public BaseFreenetStore(String storeFilename, String headerStoreFilename, long maxBlocks) throws Exception {
        dataStore = new DataStore(new File(storeFilename), new File(storeFilename+".index"), DATA_BLOCK_SIZE, maxBlocks);
        // FIXME: What's the right size? 512 is probably enough for SSKs?
        headersStore = new DataStore(new File(headerStoreFilename), new File(headerStoreFilename+".index"), HEADER_BLOCK_SIZE, maxBlocks);
    }

    /**
     * Retrieve a block.
     * @return null if there is no such block stored, otherwise the block.
     */
    public synchronized CHKBlock fetch(NodeCHK chk, boolean dontPromote) throws IOException {
        byte[] data = dataStore.getDataForBlock(chk, dontPromote);
        if(data == null) {
            if(headersStore.getDataForBlock(chk, true) != null) {
                Logger.normal(this, "Deleting: "+chk+" headers, no data");
                headersStore.delete(chk);
            }
            return null;
        }
        byte[] headers = headersStore.getDataForBlock(chk, dontPromote);
        if(headers == null) {
            // No headers, delete
            Logger.normal(this, "Deleting: "+chk+" data, no headers");
            dataStore.delete(chk);
            return null;
        }
        // Decode
        int headerLen = ((headers[0] & 0xff) << 8) + (headers[1] & 0xff);
        if(headerLen > HEADER_BLOCK_SIZE-2) {
            Logger.normal(this, "Invalid header data on "+chk+", deleting");
            dataStore.delete(chk);
            headersStore.delete(chk);
            return null;
        }
        byte[] buf = new byte[headerLen];
        System.arraycopy(headers, 2, buf, 0, headerLen);
        Logger.minor(this, "Get key: "+chk);
        Logger.minor(this, "Raw headers: "+headers.length+" bytes, hash "+Fields.hashCode(headers));
        Logger.minor(this, "Headers: "+headerLen+" bytes, hash "+Fields.hashCode(buf));
        Logger.minor(this, "Data: "+data.length+" bytes, hash "+Fields.hashCode(data));
        try {
            return new CHKBlock(data, buf, chk);
        } catch (CHKVerifyException e) {
            Logger.normal(this, "Does not verify, deleting: "+chk);
            dataStore.delete(chk);
            headersStore.delete(chk);
            return null;
        }
    }

    /**
     * Store a block.
     */
    public synchronized void put(CHKBlock block) throws IOException {
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
        Logger.minor(this, "Put key: "+block.getKey());
        Logger.minor(this, "Raw headers: "+hbuf.length+" bytes, hash "+Fields.hashCode(hbuf));
        Logger.minor(this, "Headers: "+hlen+" bytes, hash "+Fields.hashCode(headers));
        Logger.minor(this, "Data: "+data.length+" bytes, hash "+Fields.hashCode(data));
        dataStore.addDataAsBlock(block.getKey(), data);
        headersStore.addDataAsBlock(block.getKey(), hbuf);
    }
}
