package freenet.client.async;

import static java.util.concurrent.TimeUnit.MINUTES;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.Random;

import freenet.clients.fcp.FCPPersistentRoot;
import freenet.crypt.RandomSource;
import freenet.node.NodeInitException;
import freenet.support.Executor;
import freenet.support.Ticker;
import freenet.support.io.FileBucket;
import freenet.support.io.PersistentTempBucketFactory;

public class ClientLayerPersister extends PersistentJobRunnerImpl {
    
    static final long INTERVAL = MINUTES.toMillis(10);
    private final File filename;
    private final FileBucket bucket;
    private boolean loaded;
    private FCPPersistentRoot root;
    private InsertCompressorTracker persistentCompressorTracker;
    private PersistentTempBucketFactory persistentTempFactory;
    private boolean killed;
    
    private static final long MAGIC = 0xd332925f3caf4aedL;
    private static final int VERSION = 1;

    /** Load everything. */
    public ClientLayerPersister(Executor executor, Ticker ticker, File filename) {
        super(executor, ticker, INTERVAL);
        this.filename = filename;
        this.bucket = new FileBucket(filename, false, false, false, false, false);
    }
    
    public void load(File ptbfDir, String ptbfPrefix, RandomSource random, Random fastWeakRandom, 
            boolean encrypt) throws NodeInitException {
        // FIXME check backups.
        if(filename.exists()) {
            InputStream fis = null;
            try {
                fis = bucket.getInputStream();
                BufferedInputStream bis = new BufferedInputStream(fis);
                ObjectInputStream ois = new ObjectInputStream(bis);
                long magic = ois.readLong();
                if(magic != MAGIC) throw new IOException("Bad magic");
                int version = ois.readInt();
                if(version != VERSION) throw new IOException("Bad version");
                root = FCPPersistentRoot.create(ois);
                persistentCompressorTracker = (InsertCompressorTracker) ois.readObject();
                persistentTempFactory = (PersistentTempBucketFactory) ois.readObject();
                persistentTempFactory.init(ptbfDir, ptbfPrefix, random, fastWeakRandom);
                return;
            } catch (IOException e) {
                // FIXME tell user more obviously.
                System.err.println("Failed to load persistent requests: "+e);
                e.printStackTrace();
            } catch (ClassNotFoundException e) {
                System.err.println("Failed to load persistent requests: "+e);
                e.printStackTrace();
            } finally {
                try {
                    if(fis != null) fis.close();
                } catch (IOException e) {
                    System.err.println("Failed to load persistent requests: "+e);
                    e.printStackTrace();
                }
            }
        }
        // From scratch
        root = new FCPPersistentRoot();
        persistentCompressorTracker = new InsertCompressorTracker();
        try {
            persistentTempFactory =
                new PersistentTempBucketFactory(ptbfDir, ptbfPrefix, random, fastWeakRandom, encrypt);
        } catch (IOException e) {
            String msg = "Could not find or create persistent temporary directory: "+e;
            e.printStackTrace();
            throw new NodeInitException(NodeInitException.EXIT_BAD_DIR, msg);
        }
        synchronized(this) {
            loaded = true;
            updateLastCheckpointed();
        }
    }

    @Override
    protected void innerCheckpoint() {
        synchronized(this) {
            if(!loaded) return;
        }
        save();
    }
    
    protected void save() {
        // FIXME backups.
        OutputStream fos = null;
        try {
            fos = bucket.getOutputStream();
            BufferedOutputStream bos = new BufferedOutputStream(fos);
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeLong(MAGIC);
            oos.writeInt(VERSION);
            oos.writeObject(root);
            oos.writeObject(persistentCompressorTracker);
            oos.writeObject(persistentTempFactory);
        } catch (IOException e) {
            System.err.println("Failed to write persistent requests: "+e);
            e.printStackTrace();
        } finally {
            try {
                if(fos != null) fos.close();
            } catch (IOException e) {
                System.err.println("Failed to write persistent requests: "+e);
                e.printStackTrace();
            }
        }
    }
    
    @Override
    public void queue(PersistentJob job, int threadPriority) throws PersistenceDisabledException {
        synchronized(this) {
            if(!loaded) throw new PersistenceDisabledException();
            if(killed) throw new PersistenceDisabledException();
        }
        super.queue(job, threadPriority);
    }
    
    public InsertCompressorTracker persistentCompressorTracker() {
        return persistentCompressorTracker;
    }
    
    public PersistentTempBucketFactory persistentTempBucketFactory() {
        return persistentTempFactory;
    }

    public synchronized void kill() {
        killed = true;
    }

    public synchronized boolean killed() {
        return killed;
    }

}
