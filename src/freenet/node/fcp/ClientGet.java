/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InsertContext;
import freenet.client.FetchException.FetchExceptionMode;
import freenet.client.async.BinaryBlob;
import freenet.client.async.BinaryBlobWriter;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientGetCallback;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientRequester;
import freenet.client.events.ClientEvent;
import freenet.client.events.ClientEventListener;
import freenet.client.events.EnterFiniteCooldownEvent;
import freenet.client.events.ExpectedFileSizeEvent;
import freenet.client.events.ExpectedHashesEvent;
import freenet.client.events.ExpectedMIMEEvent;
import freenet.client.events.SendingToNetworkEvent;
import freenet.client.events.SplitfileCompatibilityModeEvent;
import freenet.client.events.SplitfileProgressEvent;
import freenet.clients.fcp.ClientGetMessage;
import freenet.clients.fcp.IdentifierCollisionException;
import freenet.clients.fcp.NotAllowedException;
import freenet.clients.fcp.PersistentRequestClient;
import freenet.keys.FreenetURI;
import freenet.node.NodeClientCore;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.io.BucketTools;
import freenet.support.io.FileBucket;
import freenet.support.io.FileUtil;
import freenet.support.io.NativeThread;
import freenet.support.io.NullBucket;
import freenet.support.io.ResumeFailedException;

/**
 * This is only here so we can migrate it to the new storage layer. This is a ClientGet, i.e. a 
 * download.
 */
public class ClientGet extends ClientRequest {

	/** Fetch context. Never passed in: always created new by the ClientGet. Therefore, we
	 * can safely delete it in requestWasRemoved(). */
	private final FetchContext fctx;
	private final ClientGetter getter;
	private final short returnType;
	private final File targetFile;
	private final File tempFile;
	/** Bucket passed in to the ClientGetter to return data in. Null unless returntype=disk */
	private Bucket returnBucket;
	private final boolean binaryBlob;

	// Verbosity bitmasks
	private static final int VERBOSITY_SPLITFILE_PROGRESS = 1;
	private static final int VERBOSITY_SENT_TO_NETWORK = 2;
	private static final int VERBOSITY_COMPATIBILITY_MODE = 4;
	private static final int VERBOSITY_EXPECTED_HASHES = 8;
	private static final int VERBOSITY_EXPECTED_TYPE = 32;
	private static final int VERBOSITY_EXPECTED_SIZE = 64;
	private static final int VERBOSITY_ENTER_FINITE_COOLDOWN = 128;

	// Stuff waiting for reconnection
	/** Did the request succeed? Valid if finished. */
	private boolean succeeded;
	/** Length of the found data */
	private long foundDataLength = -1;
	/** MIME type of the found data */
	private String foundDataMimeType;
	/** Details of request failure */
	private GetFailedMessage getFailedMessage;
	/** Succeeded but failed to return data e.g. couldn't write to file */
	private ProtocolErrorMessage postFetchProtocolErrorMessage;
	/** AllData (the actual direct-send data) - do not persist, because the bucket
	 * is not persistent. FIXME make the bucket persistent! */
	private AllDataMessage allDataPending;
	/** Have we received a SendingToNetworkEvent? */
	private boolean sentToNetwork;
	private CompatibilityMode compatMessage;
	private ExpectedHashes expectedHashes;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
    @Override
    public freenet.clients.fcp.ClientGet migrate(PersistentRequestClient newClient, 
            ObjectContainer container, NodeClientCore core) throws IdentifierCollisionException, NotAllowedException, IOException, ResumeFailedException {
        container.activate(fctx, Integer.MAX_VALUE);
        container.activate(uri, Integer.MAX_VALUE);
        container.activate(targetFile, Integer.MAX_VALUE);
        File f = new File(targetFile.toString()); // Db4o can do odd things with files, best to copy
        boolean realTime = false;
        if(lowLevelClient != null) {
            container.activate(lowLevelClient, Integer.MAX_VALUE);
            realTime = lowLevelClient.realTimeFlag();
        }
        freenet.clients.fcp.ClientGet ret =
            new freenet.clients.fcp.ClientGet(newClient, uri, fctx.localRequestOnly, fctx.ignoreStore, 
                fctx.filterData, fctx.maxSplitfileBlockRetries, fctx.maxNonSplitfileRetries, 
                fctx.maxOutputLength, returnType, false, identifier, verbosity, priorityClass,
                f, charset, fctx.canWriteClientCache, realTime, core);
        if(finished) {
            ClientContext context = core.clientContext;
            if(foundDataLength <= 0) throw new ResumeFailedException("No data");
            ret.receive(new ExpectedFileSizeEvent(foundDataLength), context);
            if(foundDataMimeType != null)
                ret.receive(new ExpectedMIMEEvent(foundDataMimeType), context);
            if(sentToNetwork)
                ret.receive(new SendingToNetworkEvent(), context);
            if(expectedHashes != null) {
                container.activate(expectedHashes, Integer.MAX_VALUE);
                if(expectedHashes.hashes != null)
                    ret.receive(new ExpectedHashesEvent(expectedHashes.hashes), context);
            }
            if(compatMessage != null) {
                container.activate(compatMessage, Integer.MAX_VALUE);
                SplitfileCompatibilityModeEvent e = compatMessage.toEvent();
                ret.receive(e, context);
            }
            if(succeeded) {
                Bucket data = null;
                if(returnType == RETURN_TYPE_DIRECT) {
                    container.activate(allDataPending, Integer.MAX_VALUE);
                    data = allDataPending.bucket;
                    if(data == null) throw new ResumeFailedException("No data");
                    data.onResume(context);
                }
                ret.setSuccessForMigration(context, completionTime, data);
            } else if(this.getFailedMessage != null) {
                container.activate(getFailedMessage, Integer.MAX_VALUE);
                ret.onFailure(getFailedMessage.getFetchException(), null);
            } else if(this.postFetchProtocolErrorMessage != null) {
                tempFile.delete();
                ret.onFailure(new FetchException(FetchExceptionMode.BUCKET_ERROR, "Failed to rename before migration. We have deleted the file."), null);
            }
        }
        return ret;
    }

	protected ClientGet() {
	    throw new UnsupportedOperationException();
	}
	
	@Override
	protected ClientRequester getClientRequest() {
		return getter;
	}

	@Override
	protected void freeData(ObjectContainer container) {
		Bucket data;
		synchronized(this) {
			data = returnBucket;
			returnBucket = null;
		}
		if(data != null) {
			if(persistenceType == PERSIST_FOREVER)
				container.activate(data, 5);
			data.free();
		}
	}

	@Override
	public boolean hasSucceeded() {
		return succeeded;
	}
	
    static final short RETURN_TYPE_DIRECT = 0; // over FCP
    static final short RETURN_TYPE_NONE = 1; // not at all; to cache only; prefetch?
    static final short RETURN_TYPE_DISK = 2; // to a file
    static final short RETURN_TYPE_CHUNKED = 3; // FIXME implement: over FCP, as decoded

	public boolean isDirect() {
		return this.returnType == RETURN_TYPE_DIRECT;
	}

	public boolean isToDisk() {
		return this.returnType == RETURN_TYPE_DISK;
	}

	public FreenetURI getURI(ObjectContainer container) {
		if(persistenceType == PERSIST_FOREVER)
			container.activate(uri, 5);
		return uri;
	}

	public long getDataSize(ObjectContainer container) {
		if(foundDataLength > 0)
			return foundDataLength;
		if(getter != null) {
			if(persistenceType == PERSIST_FOREVER)
				container.activate(getter, 1);
			return getter.expectedSize();
		}
		return -1;
	}

	public String getMIMEType(ObjectContainer container) {
		if(foundDataMimeType != null)
			return foundDataMimeType;
		if(getter != null) {
			if(persistenceType == PERSIST_FOREVER)
				container.activate(getter, 1);
			return getter.expectedMIME();
		}
		return null;
	}

	public File getDestFilename(ObjectContainer container) {
		if(persistenceType == PERSIST_FOREVER)
			container.activate(targetFile, 5);
		return targetFile;
	}

	public InsertContext.CompatibilityMode[] getCompatibilityMode(ObjectContainer container) {
		if(persistenceType == PERSIST_FOREVER && compatMessage != null)
			container.activate(compatMessage, 2);
		if(compatMessage != null) {
			return compatMessage.getModes();
		} else
			return new InsertContext.CompatibilityMode[] { InsertContext.CompatibilityMode.COMPAT_UNKNOWN, InsertContext.CompatibilityMode.COMPAT_UNKNOWN };
	}
	
	public boolean getDontCompress(ObjectContainer container) {
		if(compatMessage == null) return false;
		if(persistenceType == PERSIST_FOREVER)
			container.activate(compatMessage, 2);
		return compatMessage.dontCompress;
	}
	
	public byte[] getOverriddenSplitfileCryptoKey(ObjectContainer container) {
		if(persistenceType == PERSIST_FOREVER && compatMessage != null)
			container.activate(compatMessage, 2);
		if(compatMessage != null) {
			return compatMessage.cryptoKey;
		} else
			return null;
	}

	@Override
	public String getFailureReason(boolean longDescription, ObjectContainer container) {
		if(getFailedMessage == null)
			return null;
		if(persistenceType == PERSIST_FOREVER)
			container.activate(getFailedMessage, 5);
		String s = getFailedMessage.shortCodeDescription;
		if(longDescription && getFailedMessage.extraDescription != null)
			s += ": "+getFailedMessage.extraDescription;
		return s;
	}
	
	GetFailedMessage getFailureMessage(ObjectContainer container) {
		if(getFailedMessage == null) return null;
		if(persistenceType == PERSIST_FOREVER)
			container.activate(getFailedMessage, 5);
		return getFailedMessage;
	}
	
	public int getFailureReasonCode(ObjectContainer container) {
		if(getFailedMessage == null)
			return -1;
		if(persistenceType == PERSIST_FOREVER)
			container.activate(getFailedMessage, 5);
		return getFailedMessage.code;
		
	}

	public Bucket getFinalBucket(ObjectContainer container) {
		synchronized(this) {
			if(!finished) return null;
			if(!succeeded) return null;
			if(persistenceType == PERSIST_FOREVER)
				container.activate(returnBucket, 1);
			return returnBucket;
		}
	}
	
	/**
	 * Returns the {@link Bucket} that contains the downloaded data.
	 *
	 * @return The data in a {@link Bucket}, or <code>null</code> if this
	 *         isn&rsquo;t applicable
	 */
	public Bucket getBucket(ObjectContainer container) {
		synchronized(this) {
			if(targetFile != null) {
				if(succeeded || tempFile == null) {
					if(persistenceType == PERSIST_FOREVER) container.activate(targetFile, 5);
					return new FileBucket(targetFile, false, true, false, false, false);
				} else {
					if(persistenceType == PERSIST_FOREVER) container.activate(tempFile, 5);
					return new FileBucket(tempFile, false, true, false, false, false);
				}
			} else return returnBucket;
		}
	}

	public synchronized boolean hasPermRedirect() {
		return getFailedMessage != null && getFailedMessage.redirectURI != null;
	}

	public boolean filterData(ObjectContainer container) {
		if(persistenceType == PERSIST_FOREVER)
			container.activate(fctx, 1);
		return fctx.filterData;
	}

}
