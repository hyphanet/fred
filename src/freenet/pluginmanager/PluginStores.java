package freenet.pluginmanager;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;

import freenet.config.SubConfig;
import freenet.crypt.AEADCryptBucket;
import freenet.node.FSParseException;
import freenet.node.Node;
import freenet.node.NodeInitException;
import freenet.node.NodeStarter;
import freenet.node.ProgramDirectory;
import freenet.support.IllegalBase64Exception;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.io.FileBucket;
import freenet.support.io.TrivialPaddedBucket;

public class PluginStores {
    
    final Node node;
    private final ProgramDirectory pluginStoresDir;
    
    public PluginStores(Node node, SubConfig installConfig) throws NodeInitException {
        this.node = node;
        pluginStoresDir = node.setupProgramDir(installConfig, "pluginStoresDir", "plugin-data", 
                "NodeClientCore.pluginStoresDir", "NodeClientCore.pluginStoresDir", null, null);
        if(!pluginStoresDir.dir().mkdirs()) {
            System.err.println("Unable to create folder for plugin data: "+pluginStoresDir.dir());
        }
    }

    public void migrateAllPluginStores(ObjectContainer container, long nodeDBHandle) {
        List<PluginStoreContainer> pscs = new ArrayList<PluginStoreContainer>();
        ObjectSet<PluginStoreContainer> stores = container.query(PluginStoreContainer.class);
        for(PluginStoreContainer psc : stores) {
            if(psc.nodeDBHandle != nodeDBHandle) continue;
            if(psc.pluginStore == null) {
                System.err.println("No pluginStore on PSC for "+psc.storeIdentifier);
                continue;
            }
            pscs.add(psc);
        }
        if(pscs.isEmpty()) {
            System.out.println("No plugin stores to migrate.");
            return;
        }
        System.out.println("Plugin stores to migrate: "+pscs.size());
        for(PluginStoreContainer psc : pscs) {
            container.activate(psc, Integer.MAX_VALUE);
            migratePluginStores(container, psc);
        }
    }
    
    /** Migrate a single PluginStore from the database to on disk 
     * @throws IOException */
    public void migratePluginStores(ObjectContainer container, PluginStoreContainer psc) {
        try {
            if(psc.pluginStore == null) return;
            writePluginStoreInner(psc.storeIdentifier, psc.pluginStore);
            // FIXME implement removal when sure it works.
            //psc.pluginStore.removeFrom(container);
            container.commit();
            System.out.println("Migrated plugin store for "+psc.storeIdentifier+" from database to disk");
        } catch (IOException e) {
            System.err.println("Unable to migrate plugin store for "+psc.storeIdentifier+" from database to disk : "+e);
        }
    }

    private void writePluginStoreInner(String storeIdentifier, PluginStore pluginStore) throws IOException {
        Bucket bucket = getPluginStoreBucket(storeIdentifier);
        OutputStream os = bucket.getOutputStream();
        try {
            if(pluginStore != null) {
                pluginStore.exportStoreAsSFS().writeTo(os);
            }
        } finally {
            os.close();
        }
    }
    
    private File getPluginStoreBucket(String storeIdentifier, boolean encrypted) {
        String filename = storeIdentifier;
        filename += ".data";
        if(encrypted)
            filename += ".crypt";
        return pluginStoresDir.file(filename);
    }

    private Bucket getPluginStoreBucket(String storeIdentifier) throws FileNotFoundException {
        boolean isEncrypted = node.isDatabaseEncrypted();
        File f = getPluginStoreBucket(storeIdentifier, isEncrypted);
        Bucket bucket = new FileBucket(f, false, false, false, false, false);
        if(isEncrypted) {
            byte[] key = node.getPluginStoreKey(storeIdentifier);
            if(key != null) {
                bucket = new TrivialPaddedBucket(bucket);
                bucket = new AEADCryptBucket(bucket, key, NodeStarter.getGlobalSecureRandom());
            }
        }
        return bucket;
    }

    public PluginStore loadPluginStore(String storeIdentifier) {
        Bucket bucket;
        try {
            bucket = getPluginStoreBucket(storeIdentifier);
        } catch (FileNotFoundException e) {
            return null;
        }
        InputStream is = null;
        try {
            try {
                is = bucket.getInputStream();
                SimpleFieldSet fs = SimpleFieldSet.readFrom(is, false, false, true, true);
                return new PluginStore(fs);
            } finally {
                // Do NOT use Closer.close().
                // We use authenticated encryption, which will throw at close() time if the file is corrupt,
                // or has been modified while the node was offline etc.
                if(is != null) is.close();
            }
        } catch (IOException e) {
            // Hence, if close() throws, we DO need to catch it here.
            System.err.println("Unable to load plugin data for "+storeIdentifier+" : "+e);
            System.err.println("This could be caused by data corruption or bugs in Freenet.");
            // FIXME crypto - possible it's caused by attack while offline.
            return null;
        } catch (IllegalBase64Exception e) {
            // Hence, if close() throws, we DO need to catch it here.
            System.err.println("Unable to load plugin data for "+storeIdentifier+" : "+e);
            System.err.println("This could be caused by data corruption or bugs in Freenet.");
            // FIXME crypto - possible it's caused by attack while offline.
            return null;
        } catch (FSParseException e) {
            // Hence, if close() throws, we DO need to catch it here.
            System.err.println("Unable to load plugin data for "+storeIdentifier+" : "+e);
            System.err.println("This could be caused by data corruption or bugs in Freenet.");
            // FIXME crypto - possible it's caused by attack while offline.
            return null;
        }
    }

    public void writePluginStore(String storeIdentifier, PluginStore store) {
        try {
            writePluginStoreInner(storeIdentifier, store);
        } catch (IOException e) {
            System.err.println("Unable to write plugin data for "+storeIdentifier+" : "+e);
        }
    }

}
