package freenet.client.async;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;

import freenet.client.ClientMetadata;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.InsertContext.CompatibilityMode;
import freenet.client.Metadata;
import freenet.client.MetadataParseException;
import freenet.client.async.SplitFileFetcherStorage.StorageFormatException;
import freenet.crypt.CRCChecksumChecker;
import freenet.keys.ClientCHKBlock;
import freenet.keys.FreenetURI;
import freenet.node.BaseSendableGet;
import freenet.support.Logger;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;
import freenet.support.io.BucketTools;
import freenet.support.io.InsufficientDiskSpaceException;
import freenet.support.io.LockableRandomAccessThing;

/** Splitfile fetcher based on keeping as much state as possible, and in particular the downloaded blocks,
 * in a single file.
 * 
 * The main goals here are:
 * 1) Minimising disk seeks. E.g. in the older versions we abstracted out block storage, this 
 * caused a lot of unnecessary seeking and copying. It's better to keep the downloaded data close 
 * together on disk. 
 * 2) Robustness. This should be robust even against moderate levels of on-disk data corruption. 
 * And it's separate for each splitfile. And it has fixed disk usage, until it completes. Some of 
 * this robustness violates layering e.g. checking blocks against the CHKs they are supposed to 
 * represent. This actually simplifies matters e.g. when decoding a segment, and allows us to not 
 * only recover from almost any error (at least in the parts that change, which are most likely to 
 * be corrupted), but also to do so efficiently.
 * 
 * The SplitFileFetcher*Storage classes manage storing the data and FEC decoding. This class deals 
 * with everything else: The interface to the rest of the client layer, selection of keys to fetch, 
 * listening for blocks, etc.
 * 
 * PERSISTENCE ROADMAP:
 * Now: Currently this class does not support persistence.
 * 
 * Near future goal: Support persistence within the database. Stay in memory, do not deactivate.
 * Store this class in the database but not its *Storage classes. Load the SplitFileFetcherStorage 
 * on loading Freenet, resuming existing downloads.
 * => Significant improvement in reliability and disk I/O. In principle we could resume downloads
 * separately from the database e.g. if it breaks. Too complicated in practice because of other
 * data structures.
 * - Require: Add "persistence", hashCode, force no deactivation, activate where necessary (when
 * dealing with other stuff). Fill in context and load storage when activated on startup.
 * 
 * Longer term goal: Implement similar code for inserts. Eliminate db4o and persist everything that
 * remains with simple checkpointed serialisation.
 * 
 * LOCKING: (this) should be taken last, because it is used by e.g. SplitFileFetcherGet.isCancelled().
 * 
 * @author toad
 */
public class SplitFileFetcherNew implements ClientGetState, SplitFileFetcherCallback, Serializable {
    
    private static final long serialVersionUID = 1L;
    private static volatile boolean logMINOR;
    static {
        Logger.registerClass(SplitFileFetcherNew.class);
    }

    private transient SplitFileFetcherStorage storage;
    /** Kept here so we can resume from storage */
    private LockableRandomAccessThing raf;
    final ClientRequester parent;
    final GetCompletionCallback cb;
    final boolean realTimeFlag;
    final FetchContext blockFetchContext;
    final long token;
    /** Storage doesn't have a ClientContext so we need one here. */
    private transient ClientContext context;
    private transient SplitFileFetcherGet getter;
    private boolean failed;
    private boolean succeeded;
    private final boolean wantBinaryBlob;
    private final boolean persistent;
    
    SplitFileFetcherNew(Metadata metadata, GetCompletionCallback rcb, ClientRequester parent,
            FetchContext fetchContext, boolean realTimeFlag, List<COMPRESSOR_TYPE> decompressors, 
            ClientMetadata clientMetadata, long token, boolean topDontCompress, 
            short topCompatibilityMode, boolean persistent, FreenetURI thisKey, boolean isFinalFetch,
            ClientContext context) 
            throws FetchException, MetadataParseException {
        this.persistent = persistent;
        this.cb = rcb;
        this.parent = parent;
        this.realTimeFlag = realTimeFlag;
        this.token = token;
        this.context = context;
        if(parent instanceof ClientGetter) {
            wantBinaryBlob = ((ClientGetter)parent).collectingBinaryBlob();
        } else {
            wantBinaryBlob = false;
        }
        blockFetchContext = new FetchContext(fetchContext, FetchContext.SPLITFILE_DEFAULT_BLOCK_MASK, true, null);
        if(parent.isCancelled())
            throw new FetchException(FetchException.CANCELLED);
        
        KeySalter salter = context.getChkFetchScheduler(realTimeFlag).getGlobalKeySalter(persistent);

        try {
            storage = new SplitFileFetcherStorage(metadata, this, decompressors, clientMetadata, 
                    topDontCompress, topCompatibilityMode, fetchContext, realTimeFlag, salter,
                    thisKey, parent.getURI(), isFinalFetch, parent.getClientDetail(), 
                    context.random, context.tempBucketFactory, 
                    persistent ? context.persistentRAFFactory : context.tempRAFFactory, 
                    persistent ? context.jobRunner : context.dummyJobRunner, 
                    context.ticker, context.memoryLimitedJobRunner, new CRCChecksumChecker(), persistent);
        } catch (InsufficientDiskSpaceException e) {
            throw new FetchException(FetchException.NOT_ENOUGH_DISK_SPACE);
        } catch (IOException e) {
            Logger.error(this, "Failed to start splitfile fetcher because of disk I/O error?: "+e, e);
            throw new FetchException(FetchException.BUCKET_ERROR, e);
        }
        long eventualLength = Math.max(storage.finalLength, metadata.uncompressedDataLength());
        cb.onExpectedSize(eventualLength, context);
        if(metadata.uncompressedDataLength() > 0)
            cb.onFinalizedMetadata();
        if(eventualLength > 0 && fetchContext.maxOutputLength > 0 && eventualLength > fetchContext.maxOutputLength)
            throw new FetchException(FetchException.TOO_BIG, eventualLength, true, clientMetadata.getMIMEType());
        getter = new SplitFileFetcherGet(this, storage);
        raf = storage.getRAF();
        if(logMINOR)
            Logger.minor(this, "Created "+(persistent?"persistent" : "transient")+" download for "+
                    thisKey+" on "+raf+" for "+this);
    }
    
    protected SplitFileFetcherNew() {
        // For serialization.
        parent = null;
        cb = null;
        realTimeFlag = false;
        blockFetchContext = null;
        token = 0;
        wantBinaryBlob = false;
        persistent = true;
    }

    @Override
    public void schedule(ClientContext context) throws KeyListenerConstructionException {
        storage.start();
        getter.schedule(context, false);
    }
    
    /** Fail the whole splitfile request when we get an IOException on writing to or reading from 
     * the on-disk storage. Can be called asynchronously by SplitFileFetcher*Storage if an 
     * off-thread job (e.g. FEC decoding) breaks, or may be called when SplitFileFetcher*Storage
     * throws.
     * @param e The IOException, generated when accessing the on-disk storage.
     */
    @Override
    public void failOnDiskError(IOException e) {
        fail(new FetchException(FetchException.BUCKET_ERROR));
    }
    
    public void failCheckedDatastoreOnly() {
        fail(new FetchException(FetchException.DATA_NOT_FOUND));
    }

    public void fail(FetchException e) {
        synchronized(this) {
            if(succeeded || failed) return;
            failed = true;
        }
        if(storage != null)
            context.getChkFetchScheduler(realTimeFlag).removePendingKeys(storage.keyListener, true);
        if(getter != null)
            getter.cancel(context);
        if(storage != null)
            storage.cancel();
        cb.onFailure(e, this, context);
    }

    @Override
    public void cancel(ClientContext context) {
        fail(new FetchException(FetchException.CANCELLED));
    }

    @Override
    public long getToken() {
        return token;
    }

    /** The splitfile download succeeded. Generate a stream and send it to the 
     * GetCompletionCallback. See bug #6063 for a better way that probably is too much complexity
     * for the benefit. */
    @Override
    public void onSuccess() {
        synchronized(this) {
            if(succeeded) {
                Logger.error(this, "Called onSuccess() twice on "+this, new Exception("debug"));
                return;
            } else {
                if(logMINOR) Logger.minor(this, "onSuccess() on "+this, new Exception("debug"));
            }
            succeeded = true;
        }
        context.getChkFetchScheduler(realTimeFlag).removePendingKeys(storage.keyListener, true);
        getter.cancel(context);
        cb.onSuccess(storage.streamGenerator(), storage.clientMetadata, storage.decompressors, 
                this, context);
        storage.finishedFetcher();
    }
    
    @Override
    public void onClosed() {
        // Don't need to do anything.
    }

    public short getPriorityClass() {
        return this.parent.getPriorityClass();
    }

    @Override
    public void setSplitfileBlocks(int requiredBlocks, int remainingBlocks) {
        parent.addMustSucceedBlocks(requiredBlocks);
        parent.addRedundantBlocks(remainingBlocks);
        parent.notifyClients(context);
    }

    @Override
    public void onSplitfileCompatibilityMode(CompatibilityMode min, CompatibilityMode max,
            byte[] customSplitfileKey, boolean compressed, boolean bottomLayer,
            boolean definitiveAnyway) {
        cb.onSplitfileCompatibilityMode(min, max, customSplitfileKey, compressed, bottomLayer, definitiveAnyway, context);
    }

    @Override
    public void queueHeal(byte[] data, byte[] cryptoKey, byte cryptoAlgorithm) {
        try {
            context.healingQueue.queue(BucketTools.makeImmutableBucket(context.tempBucketFactory, data), cryptoKey, cryptoAlgorithm, context);
        } catch (IOException e) {
            // Nothing to be done, but need to log the error.
            Logger.error(this, "I/O error, failed to queue healing block: "+e, e);
        }
    }

    public boolean localRequestOnly() {
        return blockFetchContext.localRequestOnly;
    }

    public void toNetwork() {
        parent.toNetwork(context);
    }

    public boolean hasFinished() {
        return failed || succeeded;
    }

    @Override
    public void onFetchedBlock() {
        parent.completedBlock(!getter.hasQueued(), context);
    }

    @Override
    public void onFailedBlock() {
        parent.failedBlock(context);
    }

    @Override
    public void maybeAddToBinaryBlob(ClientCHKBlock block) {
        if(parent instanceof ClientGetter) {
            ((ClientGetter)parent).addKeyToBinaryBlob(block, context);
        }
    }

    @Override
    public boolean wantBinaryBlob() {
        return wantBinaryBlob;
    }

    @Override
    public BaseSendableGet getSendableGet() {
        return getter;
    }

    @Override
    public void restartedAfterDataCorruption() {
        if(hasFinished()) return;
        Logger.error(this, "Restarting download "+this+" after data corruption");
        // We need to fetch more blocks. Some of them may even be in the datastore.
        getter.unregister(context, getPriorityClass());
        try {
            getter.schedule(context, false);
        } catch (KeyListenerConstructionException e) {
            // Impossible.
        }
    }

    @Override
    public void clearCooldown() {
        if(hasFinished()) return;
        context.cooldownTracker.clearCachedWakeup(getter);
    }

    @Override
    public HasKeyListener getHasKeyListener() {
        return getter;
    }

    @Override
    public void onResume(ClientContext context) throws FetchException {
        Logger.error(this, "Restarting SplitFileFetcher from storage...");
        this.context = context;
        try {
            KeySalter salter = context.getChkFetchScheduler(realTimeFlag).getGlobalKeySalter(persistent);
            this.storage = new SplitFileFetcherStorage(raf, realTimeFlag, this, blockFetchContext, 
                    context.random, context.jobRunner, context.ticker, 
                    context.memoryLimitedJobRunner, new CRCChecksumChecker(), 
                    context.jobRunner.newSalt(), salter);
        } catch (IOException e) {
            raf.free();
            Logger.error(this, "Failed to resume due to I/O error: "+e+" raf = "+raf, e);
            throw new FetchException(FetchException.BUCKET_ERROR, e);
        } catch (StorageFormatException e) {
            raf.free();
            Logger.error(this, "Failed to resume due to storage error: "+e+" raf = "+raf, e);
            throw new FetchException(FetchException.INTERNAL_ERROR, "Resume failed: "+e, e);
        } catch (FetchException e) {
            raf.free();
            throw e;
        }
        getter = new SplitFileFetcherGet(this, storage);
        try {
            getter.schedule(context, storage.hasCheckedStore());
        } catch (KeyListenerConstructionException e) {
            Logger.error(this, "Key listener construction failed during resume: "+e, e);
            fail(new FetchException(FetchException.INTERNAL_ERROR, "Resume failed: "+e, e));
            return;
        }
        raf.onResume(context);
    }

}
