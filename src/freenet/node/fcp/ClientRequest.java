package freenet.node.fcp;

import java.io.*;
import java.net.*;

import freenet.client.async.*;
import freenet.keys.*;
import freenet.support.*;
import freenet.support.api.*;
import freenet.support.io.*;

/**
 * A request process carried out by the node for an FCP client.
 * Examples: ClientGet, ClientPut, MultiGet.
 */
public abstract class ClientRequest {

	/** URI to fetch, or target URI to insert to */
	protected FreenetURI uri;
	/** Unique request identifier */
	protected final String identifier;
	/** Verbosity level. Relevant to all ClientRequests, although they interpret it
	 * differently. */
	protected final int verbosity;
	/** Original FCPConnectionHandler. Null if persistence != connection */
	protected final FCPConnectionHandler origHandler;
	/** Client */
	protected final FCPClient client;
	/** Priority class */
	protected short priorityClass;
	/** Persistence type */
	protected final short persistenceType;
	/** Has the request finished? */
	protected boolean finished;
	/** Client token (string to feed back to the client on a Persistent* when he does a
	 * ListPersistentRequests). */
	protected String clientToken;
	/** Is the request on the global queue? */
	protected final boolean global;

	public ClientRequest(FreenetURI uri2, String identifier2, int verbosity2, FCPConnectionHandler handler, 
			FCPClient client, short priorityClass2, short persistenceType2, String clientToken2, boolean global) {
		this.uri = uri2;
		this.identifier = identifier2;
		if(global)
			this.verbosity = Integer.MAX_VALUE;
		else
			this.verbosity = verbosity2;
		this.finished = false;
		this.priorityClass = priorityClass2;
		this.persistenceType = persistenceType2;
		this.clientToken = clientToken2;
		this.global = global;
		if(persistenceType == PERSIST_CONNECTION)
			this.origHandler = handler;
		else
			origHandler = null;
		this.client = client;
	}
	
	public ClientRequest(FreenetURI uri2, String identifier2, int verbosity2, FCPConnectionHandler handler, 
			short priorityClass2, short persistenceType2, String clientToken2, boolean global) {
		this.uri = uri2;
		this.identifier = identifier2;
		if(global)
			this.verbosity = Integer.MAX_VALUE;
		else
			this.verbosity = verbosity2;
		this.finished = false;
		this.priorityClass = priorityClass2;
		this.persistenceType = persistenceType2;
		this.clientToken = clientToken2;
		this.global = global;
		if(persistenceType == PERSIST_CONNECTION)
			this.origHandler = handler;
		else
			origHandler = null;
		if(global) {
			client = handler.server.globalClient;
		} else {
			client = handler.getClient();
		}
	}

	public ClientRequest(SimpleFieldSet fs, FCPClient client2) throws MalformedURLException {
		priorityClass = Short.parseShort(fs.get("PriorityClass"));
		uri = new FreenetURI(fs.get("URI"));
		identifier = fs.get("Identifier");
		// We don't force the verbosity even if the request is meant to go on the global queue
		verbosity = Integer.parseInt(fs.get("Verbosity"));
		persistenceType = ClientRequest.parsePersistence(fs.get("Persistence"));
		if(persistenceType == ClientRequest.PERSIST_CONNECTION)
			throw new IllegalArgumentException("Reading persistent get with type CONNECTION !!");
		if(!((persistenceType == ClientRequest.PERSIST_FOREVER) || (persistenceType == ClientRequest.PERSIST_REBOOT)))
			throw new IllegalArgumentException("Unknown persistence type "+ClientRequest.persistenceTypeString(persistenceType));
		this.client = client2;
		this.origHandler = null;
		clientToken = fs.get("ClientToken");
		finished = Fields.stringToBool(fs.get("Finished"), false);
		global = Fields.stringToBool(fs.get("Global"), false);
	}

	/** Lost connection */
	public abstract void onLostConnection();
	
	/** Send any pending messages for a persistent request e.g. after reconnecting */
	public abstract void sendPendingMessages(FCPConnectionOutputHandler handler, boolean includePersistentRequest, boolean includeData, boolean onlyData);

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

	public static ClientRequest readAndRegister(BufferedReader br, FCPServer server) throws IOException {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, ClientRequest.class);
		Runtime rt = Runtime.getRuntime();;
		if(logMINOR)
			Logger.minor(ClientRequest.class, rt.maxMemory()-rt.freeMemory()+" in use before loading request");
		SimpleFieldSet fs = new SimpleFieldSet(br, false, false); // can get enormous
		String clientName = fs.get("ClientName");
		boolean isGlobal = Fields.stringToBool(fs.get("Global"), false);
		if(clientName == null && !isGlobal) {
			Logger.error(ClientRequest.class, "Discarding old request with no ClientName: "+fs);
			System.err.println("Discarding old request with no ClientName (see logs)");
			return null;
		}
		FCPClient client;
		if(!isGlobal)
			client = server.registerClient(clientName, server.core, null);
		else
			client = server.globalClient;
		if(logMINOR)
			Logger.minor(ClientRequest.class, rt.maxMemory()-rt.freeMemory()+" in use loading request "+clientName+" "+fs.get("Identifier"));
		try {
			String type = fs.get("Type");
			boolean lazyResume = server.core.lazyResume();
			if(type.equals("GET")) {
				ClientGet cg = new ClientGet(fs, client);
				client.register(cg, lazyResume);
				if(!lazyResume) cg.start();
				return cg;
			} else if(type.equals("PUT")) {
				ClientPut cp = new ClientPut(fs, client);
				client.register(cp, lazyResume);
				if(!lazyResume) cp.start();
				return cp;
			} else if(type.equals("PUTDIR")) {
				ClientPutDir cp = new ClientPutDir(fs, client);
				client.register(cp, lazyResume);
				if(!lazyResume) cp.start();
				return cp;
			} else {
				Logger.error(ClientRequest.class, "Unrecognized type: "+type);
				return null;
			}
		} catch (PersistenceParseException e) {
			Logger.error(ClientRequest.class, "Failed to parse request: "+e, e);
			return null;
		} catch (Throwable t) {
			Logger.error(ClientRequest.class, "Failed to parse: "+t, t);
			return null;
		}
	}

	public void cancel() {
		ClientRequester cr = getClientRequest();
		// It might have been finished on startup.
		if(cr != null) cr.cancel();
		freeData();
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
	
	/** Completed request dropped off the end without being acknowledged */
	public void dropped() {
		cancel();
		freeData();
	}
	
	/** Return the priority class */
	public short getPriority(){
		return priorityClass;
	}

	/** Free cached data bucket(s) */
	protected abstract void freeData(); 

	/** Request completed. But we may have to stick around until we are acked. */
	protected void finish() {
		if(persistenceType == ClientRequest.PERSIST_CONNECTION)
			origHandler.finishedClientRequest(this);
		else
			client.server.forceStorePersistentRequests();
		client.finishedClientRequest(this);
	}
	
	/**
	 * Write a persistent request to disk.
	 * @throws IOException 
	 */
	public void write(BufferedWriter w) throws IOException {
		if(persistenceType == ClientRequest.PERSIST_CONNECTION) {
			Logger.error(this, "Not persisting as persistenceType="+persistenceType);
			return;
		}
		// Persist the request to disk
		SimpleFieldSet fs = getFieldSet();
		fs.writeTo(w);
	}
	
	/**
	 * Get a SimpleFieldSet representing this request.
	 */
	public abstract SimpleFieldSet getFieldSet() throws IOException;

	public abstract double getSuccessFraction();
	
	public abstract double getTotalBlocks();
	public abstract double getMinBlocks();
	public abstract double getFetchedBlocks();
	public abstract double getFailedBlocks();
	public abstract double getFatalyFailedBlocks();

	public abstract String getFailureReason();

	/**
	 * Has the total number of blocks to insert been determined yet?
	 */
	public abstract boolean isTotalFinalized();
	
	public void onMajorProgress() {
		if(persistenceType != ClientRequest.PERSIST_CONNECTION) {
			if(client != null)
				client.server.forceStorePersistentRequests();
		}
	}

	/** Start the request, if it has not already been started. */
	public abstract void start();

	protected boolean started;
	
	public boolean isStarted() {
		return started;
	}

	public abstract boolean hasSucceeded();

	public abstract boolean canRestart();

	public abstract boolean restart();

    protected abstract FCPMessage persistentTagMessage();

    /**
     * Called after a ModifyPersistentRequest.
     * Sends a PersistentRequestModified message to clients if any value changed. 
     */
    public void modifyRequest(String newClientToken, short newPriorityClass) {

        boolean clientTokenChanged = false;
        boolean priorityClassChanged = false;
        
        if(newClientToken != null) {
            if( clientToken != null ) {
                if( !newClientToken.equals(clientToken) ) {
                    this.clientToken = newClientToken; // token changed
                    clientTokenChanged = true;
                }
            } else {
                this.clientToken = newClientToken; // first time the token is set
                clientTokenChanged = true;
            }
        }

        if(newPriorityClass >= 0 && newPriorityClass != priorityClass) {
            this.priorityClass = newPriorityClass;
            getClientRequest().setPriorityClass(priorityClass);
            priorityClassChanged = true;
        }

        if( clientTokenChanged || priorityClassChanged ) {
            if(persistenceType != ClientRequest.PERSIST_CONNECTION) {
                if(client != null) {
                    client.server.forceStorePersistentRequests();
                }
            }
        } else {
            return; // quick return, nothing was changed
        }

        // this could become too complex with more parameters, but for now its ok
        final PersistentRequestModifiedMessage modifiedMsg;
        if( clientTokenChanged && priorityClassChanged ) {
            modifiedMsg = new PersistentRequestModifiedMessage(identifier, global, priorityClass, clientToken);
        } else if( priorityClassChanged ) {
            modifiedMsg = new PersistentRequestModifiedMessage(identifier, global, priorityClass);
        } else if( clientTokenChanged ) {
            modifiedMsg = new PersistentRequestModifiedMessage(identifier, global, clientToken);
        } else {
            return; // paranoia, we should not be here if nothing was changed!
        }
        client.queueClientRequestMessage(modifiedMsg, 0);
    }

    /**
     * Called after a RemovePersistentRequest. Send a PersistentRequestRemoved to the clients.
     */
    public abstract void requestWasRemoved();
    
	/** Utility method for storing details of a possibly encrypted bucket. */
	protected void bucketToFS(SimpleFieldSet fs, String name, boolean includeSize, Bucket data) {
		SerializableToFieldSetBucket bucket = (SerializableToFieldSetBucket) data;
		fs.put(name, bucket.toFieldSet());
	}
}
