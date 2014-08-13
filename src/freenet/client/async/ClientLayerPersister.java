package freenet.client.async;

import static java.util.concurrent.TimeUnit.MINUTES;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import freenet.clients.fcp.ClientRequest;
import freenet.clients.fcp.RequestIdentifier;
import freenet.crypt.CRCChecksumChecker;
import freenet.crypt.ChecksumChecker;
import freenet.crypt.ChecksumFailedException;
import freenet.node.Node;
import freenet.node.NodeInitException;
import freenet.node.RequestStarterGroup;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.Ticker;
import freenet.support.io.DelayedFreeBucket;
import freenet.support.io.FileBucket;
import freenet.support.io.FileUtil;
import freenet.support.io.PersistentTempBucketFactory;
import freenet.support.io.PrependLengthOutputStream;
import freenet.support.io.StorageFormatException;
import freenet.support.io.TempBucketFactory;

public class ClientLayerPersister extends PersistentJobRunnerImpl {
    
    static final long INTERVAL = MINUTES.toMillis(10);
    private final File filename;
    private final File backupFilename;
    private final FileBucket bucket;
    private final FileBucket backupBucket;
    private final Node node; // Needed for bandwidth stats putter
    private final PersistentTempBucketFactory persistentTempFactory;
    /** Needed for temporary storage when writing objects. Some of them might be big, e.g. site 
     * inserts. */
    private final TempBucketFactory tempBucketFactory;
    private final PersistentStatsPutter bandwidthStatsPutter;
    private byte[] salt;
    private boolean newSalt;
    private final ChecksumChecker checker;
    
    private static final long MAGIC = 0xd332925f3caf4aedL;
    private static final int VERSION = 1;
    
    private static volatile boolean logMINOR;
    static {
        Logger.registerClass(ClientLayerPersister.class);
    }
    
    /** Load everything.
     * @param persistentTempFactory Only passed in so that we can call its pre- and post- commit
     * hooks. We don't explicitly save it; it must be populated lazily in onResume() like 
     * everything else. */
    public ClientLayerPersister(Executor executor, Ticker ticker, File filename, Node node,
            PersistentTempBucketFactory persistentTempFactory, TempBucketFactory tempBucketFactory,
            PersistentStatsPutter stats) {
        super(executor, ticker, INTERVAL);
        this.filename = filename;
        this.backupFilename = new File(filename.getParentFile(), filename.getName()+".bak");
        this.bucket = new FileBucket(filename, false, false, false, false, false);
        this.backupBucket = new FileBucket(backupFilename, true, false, false, false, false);
        this.node = node;
        this.persistentTempFactory = persistentTempFactory;
        this.tempBucketFactory = tempBucketFactory;
        this.checker = new CRCChecksumChecker();
        this.bandwidthStatsPutter = stats;
    }
    
    private enum RequestLoadStatus {
        // In order of preference, best first.
        LOADED,
        RESTORED_FULLY,
        RESTORED_RESTARTED,
        FAILED
    }
    
    private class PartiallyLoadedRequest {
        final ClientRequest request;
        final RequestLoadStatus status;
        PartiallyLoadedRequest(ClientRequest request, RequestLoadStatus status) {
            this.request = request;
            this.status = status;
        }
    }
    
    private class PartialLoad {
        private final Map<RequestIdentifier, PartiallyLoadedRequest> partiallyLoadedRequests 
            = new HashMap<RequestIdentifier, PartiallyLoadedRequest>();
        
        private byte[] salt;
        
        private boolean somethingFailed;
        
        /** Add a partially loaded request. 
         * @param reqID The request identifier. Must be non-null; caller should regenerate it if
         * necessary. */
        void addPartiallyLoadedRequest(RequestIdentifier reqID, ClientRequest request, 
                RequestLoadStatus status) {
            PartiallyLoadedRequest old = partiallyLoadedRequests.get(reqID);
            if(old == null || old.status.ordinal() > status.ordinal()) {
                partiallyLoadedRequests.put(reqID, new PartiallyLoadedRequest(request, status));
                if(!(status == RequestLoadStatus.LOADED || status == RequestLoadStatus.RESTORED_FULLY))
                    somethingFailed = true;
            }
        }

        public boolean isEmpty() {
            return partiallyLoadedRequests.isEmpty();
        }

        public void setSomethingFailed() {
            somethingFailed = true;
        }
        
        public boolean somethingFailed() {
            return somethingFailed;
        }

        public void setSalt(byte[] loadedSalt) {
            if(salt == null)
                salt = loadedSalt;
        }

        public byte[] getSalt() {
            return salt;
        }
    }
    
    public void load(ClientContext context, RequestStarterGroup requestStarters, Random random) throws NodeInitException {
        PartialLoad loaded = new PartialLoad();
        if(filename.exists()) {
            innerLoad(loaded, filename, true, context, requestStarters, random);
            if(loaded.somethingFailed()) {
                if(backupFilename.exists()) {
                    Logger.error(this, "Downloads/uploads queue: errors loading from "+filename.toString()+" so trying to fill in gaps by loading from backup "+backupFilename.toString());
                    System.err.println("Errors restoring downloads/uploads queue from "+filename+" so trying "+backupFilename);
                    System.err.println("Some downloads/uploads may be lost or restarted");
                    innerLoad(loaded, backupFilename, false, context, requestStarters, random);
                } else {
                    Logger.error(this, "Some errors loading from "+filename+" and no backup file available.");
                    System.err.println("Some errors restoring the downloads/uploads queue from "+filename+" but no backup file available.");
                    System.err.println("Some of your downloads/uploads may have been lost or restarted");
                }
            }
        } else {
            Logger.warning(this, filename.toString()+" does not exist so loading from backup "+backupFilename.toString());
            innerLoad(loaded, backupFilename, true, context, requestStarters, random);
        }
        
        if(loaded.getSalt() == null) {
            salt = new byte[32];
            random.nextBytes(salt);
            Logger.error(this, "Checksum failed for salt value");
            System.err.println("Salt value corrupted, downloads will need to regenerate Bloom filters, this may cause some delay and disk/CPU usage...");
            newSalt = true;
        } else {
            salt = loaded.salt;
        }
        if(!loaded.isEmpty()) {
            onLoading();
            // Resume the requests.
            for(PartiallyLoadedRequest partial : loaded.partiallyLoadedRequests.values()) {
                ClientRequest req = partial.request;
                try {
                    req.onResume(context);
                    if(partial.status == RequestLoadStatus.RESTORED_FULLY || 
                            partial.status == RequestLoadStatus.RESTORED_RESTARTED) {
                        req.start(context);
                    }
                } catch (Throwable t) {
                    System.err.println("Unable to resume request "+req+" after loading it.");
                    Logger.error(this, "Unable to resume request "+req+" after loading it.");
                    try {
                        req.cancel(context);
                    } catch (Throwable t1) {
                        Logger.error(this, "Unable to terminate "+req+" after failure");
                    }
                }
            }
            System.out.println("Resumed from saved requests ...");
            onStarted();
            return;
        } else {
            // FIXME backups etc!
            System.err.println("Starting request persistence layer without resuming ...");
            salt = new byte[32];
            random.nextBytes(salt);
            requestStarters.setGlobalSalt(salt);
            onStarted();
        }
    }
    
    private void innerLoad(PartialLoad loaded, File filename, boolean latest, 
            ClientContext context, RequestStarterGroup requestStarters, Random random) 
    throws NodeInitException {
        long length = filename.length();
        InputStream fis = null;
        ClientRequest[] requests;
        boolean[] recovered;
        try {
            // Read everything in first.
            fis = bucket.getInputStream();
            BufferedInputStream bis = new BufferedInputStream(fis);
            ObjectInputStream ois = new ObjectInputStream(bis);
            long magic = ois.readLong();
            if(magic != MAGIC) throw new IOException("Bad magic");
            int version = ois.readInt();
            if(version != VERSION) throw new IOException("Bad version");
            byte[] salt = new byte[32];
            try {
                checker.readAndChecksum(ois, salt, 0, salt.length);
                loaded.setSalt(salt);
            } catch (ChecksumFailedException e1) {
                Logger.error(this, "Unable to read global salt from "+filename+" (checksum failed)");
            }
            requestStarters.setGlobalSalt(salt);
            int requestCount = ois.readInt();
            requests = new ClientRequest[requestCount];
            recovered = new boolean[requestCount];
            for(int i=0;i<requestCount;i++) {
                RequestIdentifier req = readRequestIdentifier(ois);
                if(req != null && context.persistentRoot.hasRequest(req)) {
                    Logger.error(this, "Not reading request because already have it");
                    skipChecksummedObject(ois, length); // Request itself
                    skipChecksummedObject(ois, length); // Recovery data
                    continue;
                }
                try {
                    requests[i] = (ClientRequest) readChecksummedObject(ois, length);
                    if(req != null) {
                        if(!req.sameIdentifier(requests[i].getRequestIdentifier())) {
                            Logger.error(this, "Request does not match request identifier, discarding");
                            requests[i] = null;
                        } else {
                            loaded.addPartiallyLoadedRequest(req, requests[i], RequestLoadStatus.LOADED);
                        }
                    }
                } catch (ChecksumFailedException e) {
                    Logger.error(this, "Failed to load request (checksum failed)");
                    System.err.println("Failed to load a request (checksum failed)");
                } catch (Throwable t) {
                    // Some more serious problem. Try to load the rest anyway.
                    Logger.error(this, "Failed to load request: "+t, t);
                    System.err.println("Failed to load a request: "+t);
                    t.printStackTrace();
                }
                if(requests[i] == null || logMINOR) {
                    try {
                        ClientRequest restored = readRequestFromRecoveryData(ois, length, req);
                        if(requests[i] == null) {
                            requests[i] = restored;
                            recovered[i] = true;
                            boolean loadedFully = restored.fullyResumed();
                            loaded.addPartiallyLoadedRequest(req, requests[i], 
                                    loadedFully ? RequestLoadStatus.RESTORED_FULLY : RequestLoadStatus.RESTORED_RESTARTED);
                        }
                    } catch (ChecksumFailedException e) {
                        if(requests[i] == null) {
                            Logger.error(this, "Failed to recovery a request (checksum failed)");
                            System.err.println("Failed to recovery a request (checksum failed)");
                        } else {
                            Logger.error(this, "Test recovery failed: Checksum failed for "+req);
                        }
                        loaded.setSomethingFailed();
                    } catch (StorageFormatException e) {
                        if(requests[i] == null) {
                            Logger.error(this, "Failed to recovery a request (storage format): "+e, e);
                            System.err.println("Failed to recovery a request (storage format): "+e);
                            e.printStackTrace();
                        } else {
                            Logger.error(this, "Test recovery failed for "+req+" : "+e, e);
                        }
                        loaded.setSomethingFailed();
                    }
                } else {
                    skipChecksummedObject(ois, length);
                }
            }
            if(latest) {
                try {
                    // Don't bother with the buckets to free or the stats unless reading from the latest version (client.dat not client.dat.bak).
                    readStatsAndBuckets(ois, length, context);
                } catch (Throwable t) {
                    Logger.error(this, "Failed to restore stats and delete old temp files: "+t, t);
                }
            }
            ois.close();
            fis = null;
        } catch (IOException e) {
            // FIXME tell user more obviously.
            Logger.error(this, "Failed to load persistent requests: "+e, e);
            System.err.println("Failed to load persistent requests: "+e);
            e.printStackTrace();
            loaded.setSomethingFailed();
        } catch (Throwable t) {
            Logger.error(this, "Failed to load persistent requests: "+t, t);
            System.err.println("Failed to load persistent requests: "+t);
            t.printStackTrace();
            loaded.setSomethingFailed();
        } finally {
            try {
                if(fis != null) fis.close();
            } catch (IOException e) {
                System.err.println("Failed to load persistent requests: "+e);
                e.printStackTrace();
            }
        }
    }

    private void readStatsAndBuckets(ObjectInputStream ois, long length, ClientContext context) throws IOException, ClassNotFoundException {
        PersistentStatsPutter storedStatsPutter = (PersistentStatsPutter) ois.readObject();
        this.bandwidthStatsPutter.addFrom(storedStatsPutter);
        int count = ois.readInt();
        DelayedFreeBucket[] buckets = new DelayedFreeBucket[count];
        for(int i=0;i<count;i++) {
            try {
                buckets[i] = (DelayedFreeBucket) readChecksummedObject(ois, length);
            } catch (ChecksumFailedException e) {
                Logger.error(this, "Failed to load a bucket to free");
            }
        }
        // Delete the unnecessary buckets.
        if(buckets != null) {
            for(DelayedFreeBucket bucket : buckets) {
                if(bucket == null) continue;
                try {
                    bucket.onResume(context);
                    if(bucket.toFree())
                        bucket.realFree();
                } catch (Throwable t) {
                    Logger.error(this, "Unable to free old bucket "+bucket, t);
                }
            }
        }
    }

    @Override
    protected void innerCheckpoint() {
        save();
    }
    
    protected void save() {
        if(filename.exists()) {
            FileUtil.renameTo(filename, backupFilename);
        }
        innerSave();
    }
    
    private void innerSave() {
        DelayedFreeBucket[] buckets = persistentTempFactory.preCommit();
        OutputStream fos = null;
        try {
            fos = bucket.getOutputStream();
            BufferedOutputStream bos = new BufferedOutputStream(fos);
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeLong(MAGIC);
            oos.writeInt(VERSION);
            checker.writeAndChecksum(oos, salt);
            ClientRequest[] requests = getRequests();
            oos.writeInt(requests.length);
            for(ClientRequest req : requests) {
                // Write the request identifier so we can skip reading the request if we already have it.
                writeRequestIdentifier(oos, req.getRequestIdentifier());
                // Write the actual request.
                writeChecksummedObject(oos, req, req.toString());
                // Write recovery data. This is just enough to restart the request from scratch, 
                // but may support continuing the request in simple cases e.g. if a fetch is now
                // just a single splitfile.
                writeRecoveryData(oos, req);
            }
            bandwidthStatsPutter.updateData(node);
            oos.writeObject(bandwidthStatsPutter);
            if(buckets == null) {
                oos.writeInt(0);
            } else {
                oos.writeInt(buckets.length);
                for(DelayedFreeBucket bucket : buckets)
                    writeChecksummedObject(oos, bucket, null);
            }
            oos.close();
            fos = null;
            System.out.println("Saved "+requests.length+" requests to client.dat");
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
    
    private void writeRecoveryData(ObjectOutputStream os, ClientRequest req) throws IOException {
        PrependLengthOutputStream oos = checker.checksumWriterWithLength(os, tempBucketFactory);
        DataOutputStream dos = new DataOutputStream(oos);
        try {
            req.getClientDetail(dos, checker);
            dos.close();
            oos = null;
        } catch (Throwable e) {
            Logger.error(this, "Unable to write recovery data for "+req+" : "+e, e);
            System.err.println("Unable to write recovery data for "+req+" : "+e);
            e.printStackTrace();
            oos.abort();
        } finally {
            if(oos != null) oos.close();
        }
    }
    
    private ClientRequest readRequestFromRecoveryData(ObjectInputStream is, long totalLength, RequestIdentifier reqID) throws IOException, ChecksumFailedException, StorageFormatException {
        InputStream tmp = checker.checksumReaderWithLength(is, this.tempBucketFactory, totalLength);
        try {
            DataInputStream dis = new DataInputStream(tmp);
            ClientRequest request = ClientRequest.restartFrom(dis, reqID, getClientContext(), checker);
            dis.close();
            dis = null;
            tmp = null;
            return request;
        } catch (Throwable t) {
            Logger.error(this, "Serialization failed: "+t, t);
            return null;
        } finally {
            if(tmp != null) tmp.close();
        }
    }

    private void writeChecksummedObject(ObjectOutputStream os, Object req, String name) throws IOException {
        PrependLengthOutputStream oos = checker.checksumWriterWithLength(os, tempBucketFactory);
        try {
            ObjectOutputStream innerOOS = new ObjectOutputStream(oos);
            innerOOS.writeObject(req);
            innerOOS.close();
            oos = null;
        } catch (Throwable e) {
            Logger.error(this, "Unable to write recovery data for "+name+" : "+e, e);
            oos.abort();
        } finally {
            if(oos != null) oos.close();
        }
    }
    
    private Object readChecksummedObject(ObjectInputStream is, long totalLength) throws IOException, ChecksumFailedException, ClassNotFoundException {
        InputStream ois = checker.checksumReaderWithLength(is, this.tempBucketFactory, totalLength);
        try {
            ObjectInputStream oo = new ObjectInputStream(ois);
            Object ret = oo.readObject();
            oo.close();
            oo = null;
            ois = null;
            return ret;
        } catch (Throwable t) {
            Logger.error(this, "Serialization failed: "+t, t);
            return null;
        } finally {
            if(ois != null) ois.close();
        }
    }

    private void skipChecksummedObject(ObjectInputStream is, long totalLength) throws IOException {
        long length = is.readLong();
        if(length > totalLength) throw new IOException("Too long: "+length+" > "+totalLength);
        FileUtil.skipFully(is, length + checker.checksumLength());
    }

    private ClientRequest[] getRequests() {
        return node.clientCore.getPersistentRequests();
    }

    public boolean newSalt() {
        return newSalt;
    }
    
    private RequestIdentifier readRequestIdentifier(DataInput is) throws IOException {
        short length = is.readShort();
        if(length <= 0) return null;
        byte[] buf = new byte[length];
        try {
            checker.readAndChecksum(is, buf, 0, length);
        } catch (ChecksumFailedException e) {
            Logger.error(this, "Checksum failed reading RequestIdentifier. This is not serious but means we will have to read the next request even if we don't need it.");
            return null;
        }
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(buf));
        try {
            return new RequestIdentifier(dis);
        } catch (IOException e) {
            Logger.error(this, "Failed to parse RequestIdentifier in spite of valid checksum (probably a bug): "+e, e);
            return null;
        }
    }
    
    private void writeRequestIdentifier(DataOutput os, RequestIdentifier req) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        OutputStream oos = checker.checksumWriter(baos);
        DataOutputStream dos = new DataOutputStream(oos);
        req.writeTo(dos);
        dos.close();
        byte[] buf = baos.toByteArray();
        os.writeShort(buf.length - checker.checksumLength());
        os.write(buf);
    }
    
}
