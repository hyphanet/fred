package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.client.InsertContext;
import freenet.client.async.ClientContext;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.support.api.Bucket;

/**
 * Base class for ClientPut and ClientPutDir.
 * Any code which can be shared between the two goes here.
 */
public abstract class ClientPutBase extends ClientRequest {

	/** Created new for each ClientPutBase, so we have to delete it in requestWasRemoved() */
	final InsertContext ctx;
	final boolean getCHKOnly;

	// Stuff waiting for reconnection
	/** Has the request succeeded? */
	protected boolean succeeded;
	/** If the request failed, how did it fail? PutFailedMessage is the most
	 * convenient way to store this (InsertException has a stack trace!).
	 */
	protected PutFailedMessage putFailedMessage;
	/** URI generated for the insert. */
	protected FreenetURI generatedURI;
	// This could be a SimpleProgress, or it could be started/finished compression.
	// Not that important, so not saved on persistence.
	// Probably saving it would conflict with later changes (full persistence at
	// ClientPutter level).
	protected FCPMessage progressMessage;
	
	/** Whether to force an early generation of the CHK */
	protected final boolean earlyEncode;

	protected final FreenetURI publicURI;
	
	/** Metadata returned instead of URI */
	protected Bucket generatedMetadata;

	public final static String SALT = "Salt";
	public final static String FILE_HASH = "FileHash";

	protected ClientPutBase() {
	    throw new UnsupportedOperationException();
	}

	static FreenetURI checkEmptySSK(FreenetURI uri, String filename, ClientContext context) {
		if("SSK".equals(uri.getKeyType()) && uri.getDocName() == null && uri.getRoutingKey() == null) {
			if(filename == null || filename.equals("")) filename = "key";
			// SSK@ = use a random SSK.
	    	InsertableClientSSK key = InsertableClientSSK.createRandom(context.random, "");
	    	return key.getInsertURI().setDocName(filename);
		} else {
			return uri;
		}
	}

	public FreenetURI getGeneratedURI(ObjectContainer container) {
		if(generatedURI == null) return null;
		container.activate(generatedURI, Integer.MAX_VALUE);
		FreenetURI ret = generatedURI.clone();
		container.deactivate(generatedURI, 1);
		return ret;
	}

	protected abstract String getTypeName();

}
