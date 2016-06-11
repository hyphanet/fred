/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.File;
import java.io.IOException;

import freenet.client.ClientMetadata;
import freenet.client.MetadataUnresolvedException;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientPutter;
import freenet.client.events.FinishedCompressionEvent;
import freenet.clients.fcp.ClientPutBase.UploadFrom;
import freenet.clients.fcp.ClientRequest.Persistence;
import freenet.clients.fcp.ClientRequest;
import freenet.clients.fcp.IdentifierCollisionException;
import freenet.clients.fcp.NotAllowedException;
import freenet.clients.fcp.PersistentRequestClient;
import freenet.keys.FreenetURI;
import freenet.node.NodeClientCore;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.RandomAccessBucket;
import freenet.support.io.BucketTools;
import freenet.support.io.ResumeFailedException;

public class ClientPut extends ClientPutBase {

	ClientPutter putter;
	private final short uploadFrom;
	/** Original filename if from disk, otherwise null. Purely for PersistentPut. */
	private final File origFilename;
	/** If uploadFrom==UPLOAD_FROM_REDIRECT, this is the target of the redirect */
	private final FreenetURI targetURI;
	private Bucket data;
	private final ClientMetadata clientMetadata;
	/** We store the size of inserted data before freeing it */
	private long finishedSize;
	/** Filename if the file has one */
	private final String targetFilename;
	/** If true, we are inserting a binary blob: No metadata, no URI is generated. */
	private final boolean binaryBlob;
	private transient boolean compressing;

	protected ClientPut() {
	    throw new UnsupportedOperationException();
	}

	@Override
	protected String getTypeName() {
		return "PUT";
	}

	public FreenetURI getFinalURI() {
		return generatedURI;
	}
	
    public static final short UPLOAD_FROM_DIRECT = 0;
    public static final short UPLOAD_FROM_DISK = 1;
    public static final short UPLOAD_FROM_REDIRECT = 2;
    
	public boolean isDirect() {
		return uploadFrom == UPLOAD_FROM_DIRECT;
	}

	public File getOrigFilename() {
		if(uploadFrom != UPLOAD_FROM_DISK)
			return null;
		return origFilename;
	}

	public long getDataSize() {
		if(data == null)
			return finishedSize;
		else {
			return data.size();
		}
	}

	public String getMIMEType() {
		return clientMetadata.getMIMEType();
	}

	public enum COMPRESS_STATE {
		/** Waiting for a slot on the compression scheduler */
		WAITING,
		/** Compressing the data */
		COMPRESSING,
		/** Inserting the data */
		WORKING
	}
	
	/** Probably not meaningful for ClientPutDir's */
	public COMPRESS_STATE isCompressing() {
		if(ctx.dontCompress) return COMPRESS_STATE.WORKING;
		synchronized(this) {
			if(progressMessage == null) return COMPRESS_STATE.WAITING; // An insert starts at compressing
			// The progress message persists... so we need to know whether we have
			// started compressing *SINCE RESTART*.
			if(compressing) return COMPRESS_STATE.COMPRESSING;
			return COMPRESS_STATE.WORKING;
		}
	}

    @Override
    public ClientRequest migrate(PersistentRequestClient newClient,
            NodeClientCore core) throws IdentifierCollisionException, NotAllowedException,
            IOException, MetadataUnresolvedException, ResumeFailedException {
        ClientContext context = core.clientContext;
        ctx.onResume();
        File f = origFilename;
        if(f != null) {
            f = new File(f.toString());
            if(!f.exists()) {
                Logger.error(this, "Not migrating insert as data has been deleted");
                return null;
            }
        }
        RandomAccessBucket data;
        if(this.data != null) {
            if(this.data.size() == 0) {
                Logger.error(this, "No data migrating insert: "+this.data);
                return null;
            }
            this.data.onResume(context);
            data = BucketTools.toRandomAccessBucket(this.data, context.getBucketFactory(true));
        } else {
            Logger.error(this, "Not migrating insert as data has been deleted (or very old download?)");
            return null;
        }
        byte[] overrideSplitfileKey = null;
        if(putter != null) {
            overrideSplitfileKey = putter.getSplitfileCryptoKey();
        }
        freenet.clients.fcp.ClientPut put = new freenet.clients.fcp.ClientPut(newClient, uri, identifier, verbosity, 
                charset, priorityClass, Persistence.FOREVER, clientToken, getCHKOnly, ctx.dontCompress, 
                ctx.maxInsertRetries, UploadFrom.getByCode(uploadFrom), f, clientMetadata.getMIMEType(), data, targetURI,
                targetFilename, earlyEncode, ctx.canWriteClientCache, ctx.forkOnCacheable, 
                ctx.extraInsertsSingleBlock, ctx.extraInsertsSplitfileHeaderBlock, 
                isRealTime(), ctx.getCompatibilityMode(), overrideSplitfileKey, binaryBlob, core);
        if(finished) {
            // Not interested in generated URI if it's not finished.
            if(putFailedMessage != null) {
                if(generatedURI == null) generatedURI = putFailedMessage.expectedURI;
            }
            if(generatedURI != null) {
                put.onGeneratedURI(generatedURI, null);
            }
            if(generatedMetadata != null) {
                generatedMetadata.onResume(context);
                put.onGeneratedMetadata(generatedMetadata, null);
            }
            if(putFailedMessage != null) {
                // HACK! We don't keep anything from the FinishedCompressionEvent anyway ...
                put.receive(new FinishedCompressionEvent(-1, 0, 0), context);
                put.onFailure(putFailedMessage.getException(), null);
            } else if(succeeded) {
                put.onSuccess(null);
            }
        }
        return put;
    }

}
