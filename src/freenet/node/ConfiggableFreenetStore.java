package freenet.node;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

import freenet.keys.CHKBlock;
import freenet.keys.NodeCHK;
import freenet.store.BaseFreenetStore;
import freenet.store.FreenetStore;
import freenet.support.config.Config;

/**
 * FreenetStore variant that can be configured by the FredConfig system.
 */
public class ConfiggableFreenetStore implements FreenetStore, Configgable {

    static Config config = new Config();
    
    static {
        config.addOption("size", 1, 32L*1024L*1024L, 1);
        config.addOption("dataStoreFilename",1,"datastore", 2);
        config.addOption("headerStoreFilename",1,"headerstore",3);
        
        config.argDesc("size", "Size of store in bytes e.g. 2.3GB");
        config.shortDesc("size", "Size of the datastore in bytes.");
        config.longDesc("size", "Size of the datastore in bytes. You can use multipliers kKmMgGtTpPeE.");
        
        config.argDesc("dataStoreFilename", "Filename");
        config.shortDesc("dataStoreFilename", "Filename of the datastore file.");
        
        config.argDesc("headerStoreFilename", "Filename");
        config.shortDesc("headerStoreFilename", "Filename of the header store.");
    }
    
    long storeSizeInBlocks = -1;
    BaseFreenetStore store;
    File datastoreFilename;
    RandomAccessFile datastoreFile;
    File headerstoreFilename;
    RandomAccessFile headerstoreFile;
    
    /**
     * Create a ConfiggableFreenetStore. Registers itself on the FredConfig,
     * and then creates itself according to the config options.
     * @param fc
     */
    public ConfiggableFreenetStore(FredConfig fc) {
        fc.register(this, config, "node.store");
    }

    // Callbacks from FredConfig
    
    public void setSize(long size) {
        long sizeInBlocks = size/32768;
        if(size <= 0) throw new IllegalArgumentException("Invalid store size "+size);
        storeSizeInBlocks = sizeInBlocks;
        if(store == null)
            tryCreateStore();
        else
            store.setCapacity(storeSizeInBlocks);
    }
    
    /**
     * If possible, create the datastore.
     */
    private void tryCreateStore() {
        // TODO Auto-generated method stub
        
    }

    public long getSize() {
        if(store != null)
            return store.getCapacity() * 32768;
        else
            return storeSizeInBlocks * 32768;
    }

    public void setDataStoreFilename(String filename) throws ConfiggableException {
        File dsFilename = new File(filename);
        if(dsFilename.equals(datastoreFilename)) return;
        try {
            RandomAccessFile raf = new RandomAccessFile(dsFilename, "rw");
            datastoreFilename = dsFilename;
            datastoreFile = raf;
            if(store == null)
                tryCreateStore();
            else
                store.setDatastoreFile(raf, datastoreFilename);
        } catch (FileNotFoundException e) {
            throw new ConfiggableException("Could not open suggested datastore file "+filename+": "+e);
        }
    }

    public String getDataStoreFilename() {
        if(store == null) {
            return datastoreFilename.getPath();
        } else
            return store.getDatastoreFilename().getPath();
    }
    
    public void setHeaderStoreFilename(String filename) throws ConfiggableException {
        File hsFilename = new File(filename);
        if(hsFilename.equals(headerstoreFilename)) return;
        try {
            RandomAccessFile raf = new RandomAccessFile(hsFilename, "rw");
            headerstoreFilename = hsFilename;
            headerstoreFile = raf;
            if(store == null)
                tryCreateStore();
            else
                store.setHeaderstoreFile(raf, headerstoreFilename);
        } catch (FileNotFoundException e) {
            throw new ConfiggableException("Could not open suggested headerstore file "+filename+": "+e);
        }
    }
    
    public String getHeaderStoreFilename() {
        if(store == null) {
            return headerstoreFilename.getPath();
        } else
            return store.getHeaderstoreFilename().getPath();
    }
    
    public CHKBlock fetch(NodeCHK chk) throws IOException {
        if(store == null)
            throw new IOException("No datastore");
        return store.fetch(chk);
    }

    public void put(CHKBlock block) throws IOException {
        if(store == null)
            throw new IOException("No datastore");
        store.put(block);
    }

}
