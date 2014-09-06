package freenet.node.fcp;

import java.net.MalformedURLException;

import com.db4o.ObjectContainer;

import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientPutCallback;
import freenet.client.events.ClientEvent;
import freenet.client.events.ClientEventListener;
import freenet.client.events.FinishedCompressionEvent;
import freenet.client.events.ExpectedHashesEvent;
import freenet.client.events.SimpleEventProducer;
import freenet.client.events.SplitfileProgressEvent;
import freenet.client.events.StartedCompressionEvent;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.io.NativeThread;

/**
 * Base class for ClientPut and ClientPutDir.
 * Any code which can be shared between the two goes here.
 */
public abstract class ClientPutBase extends ClientRequest {

	/** Created new for each ClientPutBase, so we have to delete it in requestWasRemoved() */
	final InsertContext ctx;
	final boolean getCHKOnly;

	// Verbosity bitmasks
	private static final int VERBOSITY_SPLITFILE_PROGRESS = 1;
	
	private static final int VERBOSITY_EXPECTED_HASHES = 8; // same as ClientGet
	private static final int VERBOSITY_PUT_FETCHABLE = 256;
	private static final int VERBOSITY_COMPRESSION_START_END = 512;

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
	private Bucket generatedMetadata;

	public final static String SALT = "Salt";
	public final static String FILE_HASH = "FileHash";

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

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
		if(persistenceType == PERSIST_FOREVER) {
			container.activate(generatedURI, Integer.MAX_VALUE);
			FreenetURI ret = generatedURI.clone();
			container.deactivate(generatedURI, 1);
			return ret;
		} else
			return generatedURI;
	}

	protected abstract String getTypeName();

	@Override
	public synchronized String getFailureReason(boolean longDescription, ObjectContainer container) {
		if(putFailedMessage == null)
			return null;
		if(persistenceType == PERSIST_FOREVER)
			container.activate(putFailedMessage, 5);
		String s = putFailedMessage.shortCodeDescription;
		if(longDescription && putFailedMessage.extraDescription != null)
			s += ": "+putFailedMessage.extraDescription;
		return s;
	}

	public PutFailedMessage getFailureMessage(ObjectContainer container) {
		if(putFailedMessage == null)
			return null;
		if(persistenceType == PERSIST_FOREVER)
			container.activate(putFailedMessage, 5);
		return putFailedMessage;
	}
	
}
