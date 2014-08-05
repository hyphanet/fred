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

import freenet.node.Node;
import freenet.node.NodeInitException;
import freenet.node.RequestStarterGroup;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.Ticker;
import freenet.support.io.DelayedFreeBucket;
import freenet.support.io.FileBucket;
import freenet.support.io.PersistentTempBucketFactory;

public class ClientLayerPersister extends PersistentJobRunnerImpl {
    
    static final long INTERVAL = MINUTES.toMillis(10);
    private final File filename;
    private final FileBucket bucket;
    private final Node node; // Needed for bandwidth stats putter
    private final PersistentTempBucketFactory persistentTempFactory;
    private PersistentStatsPutter bandwidthStatsPutter;
    private byte[] salt;
    
    private static final long MAGIC = 0xd332925f3caf4aedL;
    private static final int VERSION = 1;

    /** Load everything.
     * @param persistentTempFactory Only passed in so that we can call its pre- and post- commit
     * hooks. We don't explicitly save it; it must be populated lazily in onResume() like 
     * everything else. */
    public ClientLayerPersister(Executor executor, Ticker ticker, File filename, Node node,
            PersistentTempBucketFactory persistentTempFactory) {
        super(executor, ticker, INTERVAL);
        this.filename = filename;
        this.bucket = new FileBucket(filename, false, false, false, false, false);
        this.node = node;
        this.persistentTempFactory = persistentTempFactory;
    }
    
    public void load(ClientContext context, RequestStarterGroup requestStarters, Random random) throws NodeInitException {
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
                salt = new byte[32];
                // FIXME checksum.
                ois.readFully(salt);
                requestStarters.setGlobalSalt(salt);
                int requests = ois.readInt();
                for(int i=0;i<requests;i++) {
                    // FIXME write a simpler, more robust, non-serialized version first.
                    ClientRequester requester = (ClientRequester) ois.readObject();
                    requester.onResume(context);
                }
                bandwidthStatsPutter = (PersistentStatsPutter) ois.readObject();
                int count = ois.readInt();
                for(int i=0;i<count;i++) {
                    DelayedFreeBucket bucket = (DelayedFreeBucket) ois.readObject();
                    try {
                        if(bucket.toFree())
                            bucket.realFree();
                    } catch (Throwable t) {
                        Logger.error(this, "Unable to free old bucket "+bucket, t);
                    }
                }
                ois.close();
                fis = null;
                System.out.println("Resumed from saved requests ...");
                onStarted();
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
        // FIXME backups etc!
        System.err.println("Starting request persistence layer without resuming ...");
        salt = new byte[32];
        random.nextBytes(salt);
        requestStarters.setGlobalSalt(salt);
        bandwidthStatsPutter = new PersistentStatsPutter();
        onStarted();
    }

    @Override
    protected void innerCheckpoint() {
        save();
    }
    
    protected void save() {
        DelayedFreeBucket[] buckets = persistentTempFactory.preCommit();
        // FIXME backups.
        OutputStream fos = null;
        try {
            fos = bucket.getOutputStream();
            BufferedOutputStream bos = new BufferedOutputStream(fos);
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeLong(MAGIC);
            oos.writeInt(VERSION);
            oos.write(salt);
            ClientRequester[] requesters = getRequesters();
            oos.writeInt(requesters.length);
            for(ClientRequester req : requesters) {
                // FIXME write a simpler, more robust, non-serialized version first.
                oos.writeObject(req);
            }
            bandwidthStatsPutter.updateData(node);
            oos.writeObject(bandwidthStatsPutter);
            oos.writeInt(buckets.length);
            for(DelayedFreeBucket bucket : buckets)
                oos.writeObject(bucket);
            oos.close();
            fos = null;
            System.out.println("Saved "+requesters.length+" requests to client.dat");
            persistentTempFactory.postCommit(buckets);
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
    
    private ClientRequester[] getRequesters() {
        return node.clientCore.getPersistentRequesters();
    }

    public PersistentStatsPutter getBandwidthStats() {
        return bandwidthStatsPutter;
    }

}
