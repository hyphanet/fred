package freenet.pluginmanager;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;

import freenet.config.SubConfig;
import freenet.node.Node;
import freenet.node.NodeInitException;
import freenet.node.ProgramDirectory;
import freenet.support.api.Bucket;
import freenet.support.io.FileBucket;

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
            Bucket bucket = writePluginStoreFile(psc.storeIdentifier);
            OutputStream os = bucket.getOutputStream();
            try {
                if(psc.pluginStore != null) {
                    psc.pluginStore.exportStoreAsSFS().writeTo(os);
                }
            } finally {
                os.close();
            }
            // FIXME implement removal when sure it works.
            //psc.pluginStore.removeFrom(container);
            container.commit();
            System.out.println("Migrated plugin store for "+psc.storeIdentifier+" from database to disk");
        } catch (IOException e) {
            System.err.println("Unable to migrate plugin store for "+psc.storeIdentifier+" from database to disk : "+e);
        }
    }

    private Bucket writePluginStoreFile(String storeIdentifier) throws FileNotFoundException {
        String filename = storeIdentifier;
        filename += ".data";
//      boolean isEncrypted = node.isDatabaseEncrypted();
//      if(isEncrypted)
//          filename += ".crypt";
        File f = pluginStoresDir.file(filename);
        Bucket bucket = new FileBucket(f, false, true, false, false, false);
        // FIXME REINSTATE
//      if(isEncrypted)
//          bucket = new CipherBucket(bucket, node.getPluginStoreKey(storeIdentifier));
        return bucket;
    }

}
