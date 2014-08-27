package freenet.client.async;

import java.io.IOException;
import java.io.Serializable;

import freenet.client.ArchiveManager.ARCHIVE_TYPE;
import freenet.client.ClientMetadata;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.client.async.SplitFileInserterSegmentStorage.MissingKeyException;
import freenet.crypt.CRCChecksumChecker;
import freenet.crypt.ChecksumFailedException;
import freenet.crypt.HashResult;
import freenet.node.SendableInsert;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;
import freenet.support.io.LockableRandomAccessThing;
import freenet.support.io.ResumeFailedException;
import freenet.support.io.StorageFormatException;

/** Top level class for a splitfile insert. Note that Storage is not persistent, it will be 
 * recreated here on resume, like in splitfile fetches. The actual status is stored in a RAF.
 * @author toad
 */
public class SplitFileInserter implements ClientPutState, Serializable, SplitFileInserterStorageCallback {
    
    private static final long serialVersionUID = 1L;
    /** Is the insert persistent? */
    private final boolean persistent;
    /** Parent ClientPutter etc */
    final BaseClientPutter parent;
    /** Callback to send Metadata, completion status etc to */
    private final PutCompletionCallback cb;
    /** The file to be inserted */
    private final LockableRandomAccessThing originalData;
    /** The RAF that stores check blocks and status info, used and created by storage. */
    private final LockableRandomAccessThing raf;
    /** Stores the state of the insert and does most of the work. */
    private transient SplitFileInserterStorage storage;
    /** Actually does the insert */
    private transient SplitFileInserterSender sender;
    /** Used any time a callback from storage needs us to do something higher level */
    private transient ClientContext context;
    /** Is the insert real-time? */
    final boolean realTime;
    /** Token to be kept with the insert */
    private final Object token;
    /** Insert settings */
    final InsertContext ctx;
    
    SplitFileInserter(boolean persistent, BaseClientPutter parent, PutCompletionCallback cb,
            LockableRandomAccessThing originalData, InsertContext ctx, ClientContext context, 
            long decompressedLength, COMPRESSOR_TYPE compressionCodec, ClientMetadata meta, 
            boolean isMetadata, ARCHIVE_TYPE archiveType, byte splitfileCryptoAlgorithm, 
            byte[] splitfileCryptoKey, byte[] hashThisLayerOnly, HashResult[] hashes,
            boolean topDontCompress, int topRequiredBlocks, int topTotalBlocks, 
            long origDataSize, long origCompressedDataSize, boolean realTime, Object token) 
            throws InsertException {
        this.persistent = persistent;
        this.parent = parent;
        this.cb = cb;
        this.originalData = originalData;
        this.context = context;
        try {
            storage = new SplitFileInserterStorage(originalData, decompressedLength, this, 
                    compressionCodec, meta, isMetadata, archiveType, 
                    context.getDiskSpaceCheckingRandomAccessThingFactory(persistent), persistent, 
                    ctx, splitfileCryptoAlgorithm, splitfileCryptoKey, hashThisLayerOnly, hashes,
                    context.tempBucketFactory /* only used for temporaries within constructor */,
                    new CRCChecksumChecker(), context.fastWeakRandom, context.memoryLimitedJobRunner,
                    context.getJobRunner(persistent), context.ticker, 
                    context.getChkInsertScheduler(realTime).fetchingKeys(), topDontCompress, 
                    topRequiredBlocks, topTotalBlocks, origDataSize, origCompressedDataSize);
        } catch (IOException e) {
            throw new InsertException(InsertException.BUCKET_ERROR, e, null);
        }
        this.raf = storage.getRAF();
        this.sender = new SplitFileInserterSender(this, storage);
        this.realTime = realTime;
        this.token = token;
        this.ctx = ctx;
    }

    @Override
    public BaseClientPutter getParent() {
        return parent;
    }

    @Override
    public void cancel(ClientContext context) {
        storage.fail(new InsertException(InsertException.CANCELLED));
    }

    @Override
    public void schedule(ClientContext context) throws InsertException {
        if(!ctx.getCHKOnly)
            sender.schedule(context);
    }

    @Override
    public Object getToken() {
        return token;
    }

    @Override
    public void onResume(ClientContext context) throws InsertException, ResumeFailedException {
        assert(persistent);
        this.context = context;
        try {
            this.storage = new SplitFileInserterStorage(raf, this, context.fastWeakRandom, 
                    context.memoryLimitedJobRunner, context.getJobRunner(true), context.ticker,
                    context.getChkInsertScheduler(realTime).fetchingKeys(), context.persistentFG, 
                    context.persistentFileTracker);
            storage.onResume(context);
            if(!originalData.equals(storage.originalData)) 
                throw new StorageFormatException("Original data restored from different filename!");
            this.sender = new SplitFileInserterSender(this, storage);
            schedule(context);
        } catch (IOException e) {
            raf.close();
            raf.free();
            // We don't free the original data. That's up to ClientPutter or the FCP level.
            throw new InsertException(InsertException.BUCKET_ERROR, e, null);
        } catch (StorageFormatException e) {
            raf.close();
            raf.free();
            // We don't free the original data. That's up to ClientPutter or the FCP level.
            throw new InsertException(InsertException.BUCKET_ERROR, e, null);
        } catch (ChecksumFailedException e) {
            raf.close();
            raf.free();
            // We don't free the original data. That's up to ClientPutter or the FCP level.
            throw new InsertException(InsertException.BUCKET_ERROR, e, null);
        }
    }

    @Override
    public void onFinishedEncode() {
        // Ignore.
    }

    @Override
    public void encodingProgress() {
        try {
            schedule(context);
        } catch (InsertException e) {
            storage.fail(e);
        }
    }

    @Override
    public void onHasKeys() {
        context.getJobRunner(persistent).queueNormalOrDrop(new PersistentJob() {

            @Override
            public boolean run(ClientContext context) {
                if(ctx.earlyEncode) {
                    try {
                        reportMetadata(storage.encodeMetadata());
                    } catch (IOException e) {
                        storage.fail(new InsertException(InsertException.BUCKET_ERROR, e, null));
                    } catch (MissingKeyException e) {
                        storage.fail(new InsertException(InsertException.BUCKET_ERROR, "Lost one or more keys", e, null));
                    }
                }
                return false;
            }
            
        });
    }

    @Override
    public void onSucceeded(final Metadata metadata) {
        context.getJobRunner(persistent).queueNormalOrDrop(new PersistentJob() {

            @Override
            public boolean run(ClientContext context) {
                if(!ctx.earlyEncode) {
                    reportMetadata(metadata);
                }
                cb.onSuccess(SplitFileInserter.this, context);
                raf.close();
                raf.free();
                return true;
            }
            
        });
    }

    protected void reportMetadata(Metadata metadata) {
        // Splitfile insert is always reportMetadataOnly, i.e. it always passes the metadata back 
        // to the parent SingleFileInserter, which will write it to a Bucket and probably insert it.
        cb.onMetadata(metadata, this, context);
    }

    @Override
    public void onFailed(final InsertException e) {
        context.getJobRunner(persistent).queueNormalOrDrop(new PersistentJob() {

            @Override
            public boolean run(ClientContext context) {
                raf.close();
                raf.free();
                cb.onFailure(e, SplitFileInserter.this, context);
                return true;
            }
        });
    }

    @Override
    public SendableInsert getSendableInsert() {
        return sender;
    }

}
