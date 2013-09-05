package freenet.client.async;

import java.io.IOException;
import java.util.List;

import com.db4o.ObjectContainer;

import freenet.client.ClientMetadata;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.Metadata;
import freenet.client.MetadataParseException;
import freenet.support.Logger;
import freenet.support.compress.Compressor;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;
import freenet.support.io.FileUtil;
import freenet.support.io.PooledRandomAccessFileWrapper;

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
 * - Require: Add "persistence", hashCode, force no deactivation, activate where necessary.
 * 
 * Longer term goal: Implement similar code for inserts. Eliminate db4o and persist everything that
 * remains with simple checkpointed serialisation.
 * 
 * @author toad
 */
public class SplitFileFetcherNew implements ClientGetState {
    
    private boolean logMINOR;
    static {
        Logger.registerClass(SplitFileFetcherNew.class);
    }

    private final SplitFileFetcherStorage storage;
    final ClientRequester parent;
    final GetCompletionCallback cb;
    final boolean realTimeFlag;
    final FetchContext blockFetchContext;
    final long token;
    
    SplitFileFetcherNew(Metadata metadata, GetCompletionCallback rcb, ClientRequester parent,
            FetchContext fetchContext, boolean realTimeFlag, List<COMPRESSOR_TYPE> decompressors, 
            ClientMetadata clientMetadata, long token, boolean topDontCompress, 
            short topCompatibilityMode, boolean persistent, ObjectContainer container, ClientContext context) 
            throws FetchException, MetadataParseException {
        this.cb = rcb;
        this.parent = parent;
        this.realTimeFlag = realTimeFlag;
        this.token = token;
        blockFetchContext = new FetchContext(fetchContext, FetchContext.SPLITFILE_DEFAULT_BLOCK_MASK, true, null);
        if(parent.isCancelled())
            throw new FetchException(FetchException.CANCELLED);
        ClientRequestSchedulerBase scheduler = context.getChkFetchScheduler(realTimeFlag).getScheduler(persistent);

        storage = new SplitFileFetcherStorage(metadata, this, decompressors, clientMetadata, 
                topDontCompress, topCompatibilityMode, fetchContext, rcb, parent, realTimeFlag, 
                persistent, scheduler, container, context);
        long eventualLength = Math.max(storage.finalLength, metadata.uncompressedDataLength());
        boolean wasActive = true;
        if(persistent) {
            wasActive = container.ext().isActive(cb);
            if(!wasActive)
                container.activate(cb, 1);
        }
        cb.onExpectedSize(eventualLength, container, context);
        if(metadata.uncompressedDataLength() > 0)
            cb.onFinalizedMetadata(container);
        if(!wasActive)
            container.deactivate(cb, 1);
        if(eventualLength > 0 && fetchContext.maxOutputLength > 0 && eventualLength > fetchContext.maxOutputLength)
            throw new FetchException(FetchException.TOO_BIG, eventualLength, true, clientMetadata.getMIMEType());
    }

    @Override
    public void schedule(ObjectContainer container, ClientContext context)
            throws KeyListenerConstructionException {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void cancel(ObjectContainer container, ClientContext context) {
        // TODO Auto-generated method stub
        
    }

    @Override
    public long getToken() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public void removeFrom(ObjectContainer container, ClientContext context) {
        // TODO Auto-generated method stub
        
    }
    
    /** Fail the whole splitfile request when we get an IOException on writing to or reading from 
     * the on-disk storage. Can be called asynchronously by SplitFileFetcher*Storage if an 
     * off-thread job (e.g. FEC decoding) breaks, or may be called when SplitFileFetcher*Storage
     * throws.
     * @param e The IOException, generated when accessing the on-disk storage.
     */
    void failOnDiskError(IOException e, ClientContext context) {
        Logger.error(this, "Splitfile download failed due to disk error: "+e, e);
        // FIXME Only call the callback once.
        // FIXME Do we want to wait for FEC decodes/encodes to finish? If we just close they may get IOE's, which we can ignore.
        // And they may still be running.
        storage.close();
        // FIXME stop fetching
        cb.onFailure(new FetchException(FetchException.BUCKET_ERROR), this, null, context);
    }

    /** The splitfile download succeeded. Generate a stream and send it to the 
     * GetCompletionCallback. See bug #6063 for a better way that probably is too much complexity
     * for the benefit. */
    public void onSuccess(ClientContext context) {
        cb.onSuccess(storage.streamGenerator(), storage.clientMetadata, storage.decompressors, 
                this, null, context);
        storage.finishedFetcher();
    }
    
    /** Called when we have finished both with sending the data to the client AND with healing 
     * blocks etc. */
    void close() {
        // All locks on RAF must have been released by this point.
        // FIXME there is more to do here
        storage.close();
    }

    public short getPriorityClass() {
        // FIXME PERSISTENCE When this is temporarily in the database we'll need to pass ObjectContainer here?
        return this.parent.getPriorityClass();
    }

}
