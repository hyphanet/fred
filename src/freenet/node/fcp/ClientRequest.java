package freenet.node.fcp;

import java.io.IOException;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.ClientRequester;
import freenet.clients.fcp.ClientGet;
import freenet.clients.fcp.IdentifierCollisionException;
import freenet.clients.fcp.NotAllowedException;
import freenet.clients.fcp.PersistentRequestClient;
import freenet.clients.fcp.PersistentRequestRoot;
import freenet.keys.FreenetURI;
import freenet.node.NodeClientCore;
import freenet.node.RequestClient;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.io.ResumeFailedException;

/**
 * This is only here so that it can be migrated.
 */
public abstract class ClientRequest {

	/** URI to fetch, or target URI to insert to */
	protected FreenetURI uri;
	/** Unique request identifier */
	protected final String identifier;
	/** Verbosity level. Relevant to all ClientRequests, although they interpret it
	 * differently. */
	protected final int verbosity;
	/** Client */
	protected final FCPClient client;
	/** Priority class */
	protected short priorityClass;
	/** Persistence type */
	protected final short persistenceType;
	/** Charset of the request's contents */
	protected final String charset;
	/** Has the request finished? */
	protected boolean finished;
	/** Client token (string to feed back to the client on a Persistent* when he does a
	 * ListPersistentRequests). */
	protected String clientToken;
	/** Is the request on the global queue? */
	protected final boolean global;
	/** Timestamp : startup time */
	protected final long startupTime;
	/** Timestamp : completion time */
	protected long completionTime;

	/** Timestamp: last activity. */
	protected long lastActivity;

	/** Kept to find out whether realtime or not */
    protected final RequestClient lowLevelClient;

	private static volatile boolean logMINOR;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	protected ClientRequest() {
	    // Created only by reading in from the database.
	    throw new UnsupportedOperationException();
	}

	// Persistence

	public static final short PERSIST_CONNECTION = 0;
	public static final short PERSIST_REBOOT = 1;
	public static final short PERSIST_FOREVER = 2;

	public static String persistenceTypeString(short type) {
		switch(type) {
		case PERSIST_CONNECTION:
			return "connection";
		case PERSIST_REBOOT:
			return "reboot";
		case PERSIST_FOREVER:
			return "forever";
		default:
			return Short.toString(type);
		}
	}

	public static short parsePersistence(String string) {
		if((string == null) || string.equalsIgnoreCase("connection"))
			return PERSIST_CONNECTION;
		if(string.equalsIgnoreCase("reboot"))
			return PERSIST_REBOOT;
		if(string.equalsIgnoreCase("forever"))
			return PERSIST_FOREVER;
		return Short.parseShort(string);
	}

	public boolean isPersistentForever() {
		return persistenceType == ClientRequest.PERSIST_FOREVER;
	}

	/** Is the request persistent? False = we can drop the request if we lose the connection */
	public boolean isPersistent() {
		return persistenceType != ClientRequest.PERSIST_CONNECTION;
	}

	public boolean hasFinished() {
		return finished;
	}

	/** Get identifier string for request */
	public String getIdentifier() {
		return identifier;
	}

	protected abstract ClientRequester getClientRequest();

	/** Return the priority class */
	public short getPriority(){
		return priorityClass;
	}

	/** Free cached data bucket(s) */
	protected abstract void freeData(ObjectContainer container); 

	public abstract String getFailureReason(boolean longDescription, ObjectContainer container);

	public void onMajorProgress(ObjectContainer container) {
		// Ignore
	}

	protected boolean started;

	public boolean isStarted() {
		return started;
	}

	public abstract boolean hasSucceeded();

	/**
	 * Returns the time of the request’s last activity, or {@code 0} if there is
	 * no known last activity.
	 *
	 * @return The time of the request’s last activity, or {@code 0}
	 */
	public long getLastActivity() {
		return lastActivity;
	}

	protected boolean isGlobalQueue() {
		if(client == null) return false;
		return client.isGlobalQueue;
	}

	public FCPClient getClient(){
		return client;
	}

    public abstract freenet.clients.fcp.ClientRequest migrate(PersistentRequestClient newClient, 
            ObjectContainer container, NodeClientCore core) throws IdentifierCollisionException, NotAllowedException, IOException, ResumeFailedException;
	
}
