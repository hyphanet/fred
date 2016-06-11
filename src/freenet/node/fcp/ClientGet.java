/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.File;
import java.io.IOException;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchException.FetchExceptionMode;
import freenet.client.async.ClientContext;
import freenet.client.events.ExpectedFileSizeEvent;
import freenet.client.events.ExpectedHashesEvent;
import freenet.client.events.ExpectedMIMEEvent;
import freenet.client.events.SendingToNetworkEvent;
import freenet.client.events.SplitfileCompatibilityModeEvent;
import freenet.clients.fcp.ClientGet.ReturnType;
import freenet.clients.fcp.IdentifierCollisionException;
import freenet.clients.fcp.NotAllowedException;
import freenet.clients.fcp.PersistentRequestClient;
import freenet.node.NodeClientCore;
import freenet.support.api.Bucket;
import freenet.support.io.ResumeFailedException;

/**
 * This is only here so we can migrate it to the new storage layer. This is a ClientGet, i.e. a 
 * download.
 */
public class ClientGet extends ClientRequest {

	/** Fetch context. Never passed in: always created new by the ClientGet. Therefore, we
	 * can safely delete it in requestWasRemoved(). */
	private final FetchContext fctx;
	//private final ClientGetter getter;
	private final short returnType;
	private final File targetFile;
	private final File tempFile;
	/** Bucket passed in to the ClientGetter to return data in. Null unless returntype=disk */
	//private Bucket returnBucket;
	private final boolean binaryBlob;

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

    @Override
    public freenet.clients.fcp.ClientGet migrate(PersistentRequestClient newClient, 
            NodeClientCore core) throws IdentifierCollisionException, NotAllowedException, IOException, ResumeFailedException {
        File f = targetFile;
        if(f != null)
            f = new File(f.toString());
        boolean realTime = isRealTime();
        freenet.clients.fcp.ClientGet ret =
            new freenet.clients.fcp.ClientGet(newClient, uri, fctx.localRequestOnly, fctx.ignoreStore, 
                fctx.filterData, fctx.maxSplitfileBlockRetries, fctx.maxNonSplitfileRetries, 
                fctx.maxOutputLength, ReturnType.getByCode(returnType), false, identifier, verbosity, priorityClass,
                f, charset, fctx.canWriteClientCache, realTime, binaryBlob, core);
        if(finished) {
            ClientContext context = core.clientContext;
            if(getFailedMessage != null) {
                if(getFailedMessage.expectedMimeType != null)
                    this.foundDataMimeType = getFailedMessage.expectedMimeType;
            }
            if(foundDataLength >= 0) {
                ret.receive(new ExpectedFileSizeEvent(foundDataLength), context);
            }
            if(foundDataMimeType != null)
                ret.receive(new ExpectedMIMEEvent(foundDataMimeType), context);
            if(sentToNetwork)
                ret.receive(new SendingToNetworkEvent(), context);
            if(expectedHashes != null) {
                if(expectedHashes.hashes != null)
                    ret.receive(new ExpectedHashesEvent(expectedHashes.hashes), context);
            }
            if(compatMessage != null) {
                SplitfileCompatibilityModeEvent e = compatMessage.toEvent();
                ret.receive(e, context);
            }
            if(succeeded) {
                if(foundDataLength <= 0) throw new ResumeFailedException("No data");
                Bucket data = null;
                if(returnType == RETURN_TYPE_DIRECT) {
                    data = allDataPending.bucket;
                    if(data == null) throw new ResumeFailedException("No data");
                    data.onResume(context);
                }
                ret.setSuccessForMigration(context, completionTime, data);
            } else if(this.getFailedMessage != null) {
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
	
    static final short RETURN_TYPE_DIRECT = 0; // over FCP
    static final short RETURN_TYPE_NONE = 1; // not at all; to cache only; prefetch?
    static final short RETURN_TYPE_DISK = 2; // to a file
    static final short RETURN_TYPE_CHUNKED = 3; // FIXME implement: over FCP, as decoded

}
