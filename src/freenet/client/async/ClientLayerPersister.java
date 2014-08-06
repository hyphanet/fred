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

import freenet.crypt.CRCChecksumChecker;
import freenet.crypt.ChecksumChecker;
import freenet.crypt.ChecksumFailedException;
import freenet.node.Node;
import freenet.node.NodeInitException;
import freenet.node.RequestStarterGroup;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.Ticker;
import freenet.support.api.Bucket;
import freenet.support.io.BucketTools;
import freenet.support.io.DelayedFreeBucket;
import freenet.support.io.FileBucket;
import freenet.support.io.PersistentTempBucketFactory;
import freenet.support.io.TempBucketFactory;

public class ClientLayerPersister extends PersistentJobRunnerImpl {
    
    static final long INTERVAL = MINUTES.toMillis(10);
    private final File filename;
    private final FileBucket bucket;
    private final Node node; // Needed for bandwidth stats putter
    private final PersistentTempBucketFactory persistentTempFactory;
    /** Needed for temporary storage when writing objects. Some of them might be big, e.g. site 
     * inserts. */
    private final TempBucketFactory tempBucketFactory;
    private PersistentStatsPutter bandwidthStatsPutter;
    private byte[] salt;
    private boolean newSalt;
    private final ChecksumChecker checker;
    
    private static final long MAGIC = 0xd332925f3caf4aedL;
    private static final int VERSION = 1;

    /** Load everything.
     * @param persistentTempFactory Only passed in so that we can call its pre- and post- commit
     * hooks. We don't explicitly save it; it must be populated lazily in onResume() like 
     * everything else. */
    public ClientLayerPersister(Executor executor, Ticker ticker, File filename, Node node,
            PersistentTempBucketFactory persistentTempFactory, TempBucketFactory tempBucketFactory) {
        super(executor, ticker, INTERVAL);
        this.filename = filename;
        this.bucket = new FileBucket(filename, false, false, false, false, false);
        this.node = node;
        this.persistentTempFactory = persistentTempFactory;
        this.tempBucketFactory = tempBucketFactory;
        this.checker = new CRCChecksumChecker();
    }
    
    public void load(ClientContext context, RequestStarterGroup requestStarters, Random random) throws NodeInitException {
        // FIXME check backups.
        if(filename.exists()) {
            long length = filename.length();
            InputStream fis = null;
            ClientRequester[] requests;
            DelayedFreeBucket[] buckets = null;
            try {
                // Read everything in first.
                fis = bucket.getInputStream();
                BufferedInputStream bis = new BufferedInputStream(fis);
                ObjectInputStream ois = new ObjectInputStream(bis);
                long magic = ois.readLong();
                if(magic != MAGIC) throw new IOException("Bad magic");
                int version = ois.readInt();
                if(version != VERSION) throw new IOException("Bad version");
                salt = new byte[32];
                try {
                    checker.readAndChecksum(ois, salt, 0, salt.length);
                } catch (ChecksumFailedException e1) {
                    random.nextBytes(salt);
                    Logger.error(this, "Checksum failed for salt value");
                    System.err.println("Salt value corrupted, downloads will need to regenerate Bloom filters, this may cause some delay and disk/CPU usage...");
                    newSalt = true;
                }
                // FIXME checksum.
                ois.readFully(salt);
                requestStarters.setGlobalSalt(salt);
                int requestCount = ois.readInt();
                requests = new ClientRequester[requestCount];
                for(int i=0;i<requestCount;i++) {
                    // FIXME write a simpler, more robust, non-serialized version first.
                    try {
                        requests[i] = (ClientRequester) readChecksummedObject(ois, length);
                    } catch (ChecksumFailedException e) {
                        Logger.error(this, "Failed to restore request (checksum failed)");
                        System.err.println("Failed to restore a request (checksum failed)");
                    } catch (Throwable t) {
                        // Some more serious problem. Try to load the rest anyway.
                        Logger.error(this, "Failed to restore request: "+t, t);
                        System.err.println("Failed to restore a request: "+t);
                        t.printStackTrace();
                    }
                }
                bandwidthStatsPutter = (PersistentStatsPutter) ois.readObject();
                int count = ois.readInt();
                buckets = new DelayedFreeBucket[count];
                for(int i=0;i<count;i++) {
                    try {
                        buckets[i] = (DelayedFreeBucket) readChecksummedObject(ois, length);
                    } catch (ChecksumFailedException e) {
                        Logger.error(this, "Failed to load a bucket to free");
                    }
                }
                ois.close();
                fis = null;
                onLoading();
                // Resume the requests.
                for(ClientRequester req : requests) {
                    if(req != null)
                        req.onResume(context);
                }
                // Delete the unnecessary buckets.
                if(buckets != null) {
                    for(DelayedFreeBucket bucket : buckets) {
                        if(bucket == null) continue;
                        bucket.onResume(context);
                        try {
                            if(bucket.toFree())
                                bucket.realFree();
                        } catch (Throwable t) {
                            Logger.error(this, "Unable to free old bucket "+bucket, t);
                        }
                    }
                }
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
            checker.writeAndChecksum(oos, salt);
            oos.write(salt);
            ClientRequester[] requesters = getRequesters();
            oos.writeInt(requesters.length);
            for(ClientRequester req : requesters) {
                writeChecksummedObject(oos, req);
            }
            bandwidthStatsPutter.updateData(node);
            oos.writeObject(bandwidthStatsPutter);
            if(buckets == null) {
                oos.writeInt(0);
            } else {
                oos.writeInt(buckets.length);
                for(DelayedFreeBucket bucket : buckets)
                    writeChecksummedObject(oos, bucket);
            }
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
    
    private void writeChecksummedObject(ObjectOutputStream os, Object req) throws IOException {
        // FIXME write a simpler, more robust, non-serialized version first.
        Bucket tmp = tempBucketFactory.makeBucket(-1);
        OutputStream tmpOS = tmp.getOutputStream();
        OutputStream checksummedOS = checker.checksumWriter(tmpOS);
        ObjectOutputStream oos = new ObjectOutputStream(checksummedOS);
        oos.writeObject(req);
        oos.close();
        os.writeLong(tmp.size() - checker.checksumLength());
        BucketTools.copyTo(tmp, os, Long.MAX_VALUE);
        tmp.free();
    }
    
    private Object readChecksummedObject(ObjectInputStream is, long totalLength) throws IOException, ChecksumFailedException, ClassNotFoundException {
        long length = is.readLong();
        if(length > totalLength) throw new IOException("Too long: "+length+" > "+totalLength);
        Bucket tmp = null;
        OutputStream os = null;
        InputStream tmpIS = null;
        try {
            tmp = tempBucketFactory.makeBucket(length);
            os = tmp.getOutputStream();
            checker.copyAndStripChecksum(is, os, length);
            os.close();
            tmpIS = tmp.getInputStream();
            return new ObjectInputStream(tmpIS).readObject();
        } finally {
            // Don't use Closer because we *DO* want the IOException's.
            if(tmpIS != null) tmpIS.close();
            if(os != null) os.close();
            if(tmp != null) tmp.free();
        }
    }

    private ClientRequester[] getRequesters() {
        return node.clientCore.getPersistentRequesters();
    }

    public PersistentStatsPutter getBandwidthStats() {
        return bandwidthStatsPutter;
    }
    
    public boolean newSalt() {
        return newSalt;
    }

}
