/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.TooManyFilesInsertException;
import freenet.client.events.FinishedCompressionEvent;
import freenet.clients.fcp.ClientRequest;
import freenet.clients.fcp.ClientRequest.Persistence;
import freenet.clients.fcp.IdentifierCollisionException;
import freenet.clients.fcp.NotAllowedException;
import freenet.clients.fcp.PersistentRequestClient;
import freenet.node.NodeClientCore;
import freenet.support.api.BucketFactory;
import freenet.support.io.ResumeFailedException;

public class ClientPutDir extends ClientPutBase {

	private HashMap<String, Object> manifestElements;
	// Note that disk puts will resume as complex puts. This is OK.
	//private ManifestPutter putter;
	//private short manifestPutterType;
	private final String defaultName;
	//private final long totalSize;
	//private final int numberOfFiles;
	//private final boolean wasDiskPut;
	
	private final byte[] overrideSplitfileCryptoKey;
	
    @Override
    public ClientRequest migrate(PersistentRequestClient newClient, ObjectContainer container,
            NodeClientCore core) throws IdentifierCollisionException, NotAllowedException,
            IOException, ResumeFailedException, TooManyFilesInsertException {
        ClientContext context = core.clientContext;
        container.activate(manifestElements, Integer.MAX_VALUE);
        migrateManifestElements(manifestElements, context.getBucketFactory(true), context);
        container.activate(uri, Integer.MAX_VALUE);
        container.activate(ctx, Integer.MAX_VALUE);
        freenet.clients.fcp.ClientPutDir put =
            new freenet.clients.fcp.ClientPutDir(newClient, uri, identifier, verbosity, 
                priorityClass, Persistence.FOREVER, clientToken, ctx.getCHKOnly, ctx.dontCompress,
                ctx.maxInsertRetries, manifestElements, defaultName, global, ctx.earlyEncode,
                ctx.canWriteClientCache, ctx.forkOnCacheable, ctx.extraInsertsSingleBlock, 
                ctx.extraInsertsSplitfileHeaderBlock, isRealTime(container), 
                this.overrideSplitfileCryptoKey, core);
        if(finished) {
            // Not interested in generated URI if it's not finished.
            if(putFailedMessage != null) {
                container.activate(putFailedMessage, Integer.MAX_VALUE);
                if(generatedURI == null) generatedURI = putFailedMessage.expectedURI;
            }
            if(generatedURI != null) {
                container.activate(generatedURI, Integer.MAX_VALUE);
                put.onGeneratedURI(generatedURI, null);
            }
            if(generatedMetadata != null) {
                container.activate(generatedMetadata, Integer.MAX_VALUE);
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
    
	private void migrateManifestElements(HashMap<String, Object> map, BucketFactory bf, ClientContext context) throws ResumeFailedException, IOException {
	    for(Map.Entry<String, Object> entry : map.entrySet()) {
	        Object val = entry.getValue();
	        if(val instanceof HashMap) {
	            migrateManifestElements((HashMap<String, Object>)val, bf, context);
	        } else if(val instanceof freenet.client.async.ManifestElement) {
	            freenet.client.async.ManifestElement oldElement = 
	                (freenet.client.async.ManifestElement) val;
	            entry.setValue(oldElement.migrate(bf, context));
	        }
	    }
    }

    /**
	 * zero arg c'tor for db4o on jamvm
	 */
	@SuppressWarnings("unused")
	protected ClientPutDir() {
	    throw new UnsupportedOperationException();
	}

	@Override
	protected String getTypeName() {
		return "PUTDIR";
	}

}
