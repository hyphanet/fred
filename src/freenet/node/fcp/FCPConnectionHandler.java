package freenet.node.fcp;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;
import freenet.client.async.DatabaseDisabledException;
import freenet.client.async.TooManyFilesInsertException;
import freenet.node.RequestClient;
import freenet.pluginmanager.PluginNotFoundException;
import freenet.support.HexUtil;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.BucketFactory;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;
import freenet.support.io.NativeThread;

public class FCPConnectionHandler implements Closeable {
	private static final class DirectoryAccess {
		final boolean canWrite;
		final boolean canRead;
		
		public DirectoryAccess(boolean canRead, boolean canWrite) {
			this.canRead = canRead;
			this.canWrite = canWrite;
		}
	}
	
	public static class DDACheckJob {
		final File directory, readFilename, writeFilename;
		final String readContent, writeContent; 
		
		/**
		 * null if not requested.
		 */
		DDACheckJob(Random r, File directory, File readFilename, File writeFilename) {
			this.directory = directory;
			this.readFilename = readFilename;
			this.writeFilename = writeFilename;
			
			byte[] random = new byte[128];
			
			r.nextBytes(random);
			this.readContent = HexUtil.bytesToHex(random);

			r.nextBytes(random);
			this.writeContent = HexUtil.bytesToHex(random);
		}
	}

	final FCPServer server;
	final Socket sock;
	final FCPConnectionInputHandler inputHandler;
	final Map<String, SubscribeUSK> uskSubscriptions;
	public final FCPConnectionOutputHandler outputHandler;
	private boolean isClosed;
	private boolean inputClosed;
	private boolean outputClosed;
	private String clientName;
	private FCPClient rebootClient;
	private FCPClient foreverClient;
	final BucketFactory bf;
	final HashMap<String, ClientRequest> requestsByIdentifier;

	/**
	 * {@link FCPPluginClient} indexed by {@link FCPPluginClient#getServerPluginName()}.
	 */
	private final TreeMap<String, FCPPluginClient> pluginClientsByServerPluginName = new TreeMap<String, FCPPluginClient>();
	
    /**
     * Lock for {@link #pluginClientsByServerPluginName}.
     * 
     * A {@link ReadWriteLock} because the usage pattern is mostly reads, very few writes - {@link ReadWriteLock} can do that faster than a regular Lock.
     * (A {@link ReentrantReadWriteLock} because thats the only implementation of {@link ReadWriteLock}.)
     */
    private final ReadWriteLock pluginClientsByServerPluginNameLock = new ReentrantReadWriteLock();

	/**
	 * 16 random bytes hex-encoded as String. Unique for each instance of this class. 
	 * @deprecated Use {@link #connectionIdentifierUUID} instead.
	 */
	@Deprecated
	protected final String connectionIdentifier;
	
	/** Random UUID unique for each instance of this class */
	protected final UUID connectionIdentifierUUID;
	
	private static volatile boolean logMINOR;
	private boolean killedDupe;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	// We are confident that the given client can access those
	private final HashMap<String, DirectoryAccess> checkedDirectories = new HashMap<String, DirectoryAccess>();
	// DDACheckJobs in flight
	private final HashMap<File, DDACheckJob> inTestDirectories = new HashMap<File, DDACheckJob>();
	public final RequestClient connectionRequestClientBulk = new RequestClient() {
		
		@Override
		public boolean persistent() {
			return false;
		}
		
		@Override
		public void removeFrom(ObjectContainer container) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean realTimeFlag() {
			return false;
		}
		
	};
	public final RequestClient connectionRequestClientRT = new RequestClient() {
		
		@Override
		public boolean persistent() {
			return false;
		}
		
		@Override
		public void removeFrom(ObjectContainer container) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean realTimeFlag() {
			return true;
		}
		
	};
	
	public FCPConnectionHandler(Socket s, FCPServer server) {
		this.sock = s;
		this.server = server;
		isClosed = false;
		this.bf = server.core.tempBucketFactory;
		requestsByIdentifier = new HashMap<String, ClientRequest>();
		uskSubscriptions = new HashMap<String, SubscribeUSK>();
		this.inputHandler = new FCPConnectionInputHandler(this);
		this.outputHandler = new FCPConnectionOutputHandler(this);
		
		byte[] identifier = new byte[16];
		server.node.random.nextBytes(identifier);
		this.connectionIdentifier = HexUtil.bytesToHex(identifier);
		
		// The random 16-byte identifier was used before we added the UUID. Luckily, UUIDs are also 16 byetes, so we can re-use the bytes.
		// TODO: When getting rid of the non-UUID connectionIdentifier, use UUID.randomUUID();
		this.connectionIdentifierUUID = UUID.nameUUIDFromBytes(identifier);
	}
	
	void start() {
		inputHandler.start();
		outputHandler.start();
	}

	@Override
	public void close() {
		ClientRequest[] requests;
		if(rebootClient != null)
			rebootClient.onLostConnection(this);
		if(foreverClient != null)
			foreverClient.onLostConnection(this);
		boolean dupe;
		SubscribeUSK[] uskSubscriptions2;
		synchronized(this) {
			if(isClosed) {
				// This is normal, both input and output handlers will call close().
				return;
			}
			isClosed = true;
			requests = new ClientRequest[requestsByIdentifier.size()];
			requests = requestsByIdentifier.values().toArray(requests);
			requestsByIdentifier.clear();
			uskSubscriptions2 = uskSubscriptions.values().toArray(new SubscribeUSK[uskSubscriptions.size()]);
			dupe = killedDupe;
		}
		for(ClientRequest req : requests)
			req.onLostConnection(null, server.core.clientContext);
		for(SubscribeUSK sub : uskSubscriptions2)
			sub.unsubscribe();
		if(!dupe) {
		try {
			server.core.clientContext.jobRunner.queue(new DBJob() {

				@Override
				public boolean run(ObjectContainer container, ClientContext context) {
					if((rebootClient != null) && !rebootClient.hasPersistentRequests(null))
						server.unregisterClient(rebootClient, null);
					if(foreverClient != null) {
						if(!container.ext().isStored(foreverClient)) {
							Logger.normal(this, "foreverClient is not stored in the database in lost connection non-dupe callback; not deleting it");
							return false;
						}
						container.activate(foreverClient, 1);
						if(!foreverClient.hasPersistentRequests(container))
							server.unregisterClient(foreverClient, container);
						container.deactivate(foreverClient, 1);
					}
					return false;
				}
				
			}, NativeThread.NORM_PRIORITY, false);
		} catch (DatabaseDisabledException e) {
			// Ignore
		}
		}
		
		outputHandler.onClosed();
	}
	
	synchronized void setKilledDupe() {
		killedDupe = true;
	}
	
	public synchronized boolean isClosed() {
		return isClosed;
	}
	
	public void closedInput() {
		try {
			sock.shutdownInput();
		} catch (IOException e) {
			// Ignore
		}
		synchronized(this) {
			inputClosed = true;
			if(!outputClosed) return;
		}
		try {
			sock.close();
		} catch (IOException e) {
			// Ignore
		}
	}
	
	public void closedOutput() {
		try {
			sock.shutdownOutput();
		} catch (IOException e) {
			// Ignore
		}
		synchronized(this) {
			outputClosed = true;
			if(!inputClosed) return;
		}
		try {
			sock.close();
		} catch (IOException e) {
			// Ignore
		}
	}

	public void setClientName(final String name) {
		this.clientName = name;
		rebootClient = server.registerRebootClient(name, server.core, this);
		rebootClient.queuePendingMessagesOnConnectionRestartAsync(outputHandler, null, server.core.clientContext);
		// Create foreverClient lazily. Everything that needs it (especially creating ClientGet's etc) runs on a database job.
		if(logMINOR)
			Logger.minor(this, "Set client name: "+name);
	}
	
	protected FCPClient createForeverClient(String name, ObjectContainer container) {
		synchronized(FCPConnectionHandler.this) {
			if(foreverClient != null) return foreverClient;
		}
		FCPClient client = server.registerForeverClient(name, server.core, FCPConnectionHandler.this, container);
		synchronized(FCPConnectionHandler.this) {
			foreverClient = client;
			FCPConnectionHandler.this.notifyAll();
		}
		client.queuePendingMessagesOnConnectionRestartAsync(outputHandler, container, server.core.clientContext);
		return foreverClient;
	}

	public String getClientName() {
		return clientName;
	}

	// FIXME next 3 methods are in need of refactoring!
	
	/**
	 * Start a ClientGet. If there is an identifier collision, queue an IdentifierCollisionMessage.
	 * Hence, we can run stuff on other threads if we need to, as long as we send the right messages.
	 */
	public void startClientGet(final ClientGetMessage message) {
		final String id = message.identifier;
		final boolean global = message.global;
		ClientGet cg = null;
		boolean success;
		boolean persistent = message.persistenceType != ClientRequest.PERSIST_CONNECTION;
		synchronized(this) {
			if(isClosed) return;
			// We need to track non-persistent requests anyway, so we may as well check
			if(persistent)
				success = true;
			else
				success = !requestsByIdentifier.containsKey(id);
			if(success) {
				try {
					
					if(!persistent) {
						cg = new ClientGet(this, message, server, null);
						requestsByIdentifier.put(id, cg);
					} else if(message.persistenceType == ClientRequest.PERSIST_FOREVER) {
						try {
							server.core.clientContext.jobRunner.queue(new DBJob() {

								@Override
								public boolean run(ObjectContainer container, ClientContext context) {
									ClientGet getter;
									try {
										getter = new ClientGet(FCPConnectionHandler.this, message, server, container);
									} catch (IdentifierCollisionException e1) {
										Logger.normal(this, "Identifier collision on "+this);
										FCPMessage msg = new IdentifierCollisionMessage(id, message.global);
										outputHandler.queue(msg);
										return false;
									} catch (MessageInvalidException e1) {
										outputHandler.queue(new ProtocolErrorMessage(e1.protocolCode, false, e1.getMessage(), e1.ident, e1.global));
										return false;
									}
									try {
										getter.register(container, false);
										container.store(getter);
									} catch (IdentifierCollisionException e) {
										Logger.normal(this, "Identifier collision on "+this);
										FCPMessage msg = new IdentifierCollisionMessage(id, global);
										outputHandler.queue(msg);
										return false;
									}
									getter.start(container, context);
									container.deactivate(getter, 1);
									return true;
								}
								
							}, NativeThread.HIGH_PRIORITY-1, false);
						} catch (DatabaseDisabledException e) {
							outputHandler.queue(new ProtocolErrorMessage(ProtocolErrorMessage.PERSISTENCE_DISABLED, false, "Persistence is disabled", id, global));
							return;
						} // user wants a response soon... but doesn't want it to block the queue page etc
						return; // Don't run the start() below
					} else {
						cg = new ClientGet(this, message, server, null);
					}
				} catch (IdentifierCollisionException e) {
					success = false;
				} catch (MessageInvalidException e) {
					outputHandler.queue(new ProtocolErrorMessage(e.protocolCode, false, e.getMessage(), e.ident, e.global));
					return;
				}
			}
		}
		if(message.persistenceType == ClientRequest.PERSIST_REBOOT)
			try {
				cg.register(null, false);
			} catch (IdentifierCollisionException e) {
				success = false;
			}
		if(!success) {
			Logger.normal(this, "Identifier collision on "+this);
			FCPMessage msg = new IdentifierCollisionMessage(id, message.global);
			outputHandler.queue(msg);
			return;
		} else {
			cg.start(null, server.core.clientContext);
		}
	}

	public void startClientPut(final ClientPutMessage message) {
		if(logMINOR)
			Logger.minor(this, "Starting insert ID=\""+message.identifier+ '"');
		final String id = message.identifier;
		final boolean global = message.global;
		ClientPut cp = null;
		boolean persistent = message.persistenceType != ClientRequest.PERSIST_CONNECTION;
		FCPMessage failedMessage = null;
		synchronized(this) {
			boolean success;
			if(isClosed) {
				if(logMINOR) Logger.minor(this, "Connection is closed");
				return;
			}
			// We need to track non-persistent requests anyway, so we may as well check
			if(persistent)
				success = true;
			else
				success = !requestsByIdentifier.containsKey(id);
			if(success) {
				if(!persistent) {
					try {
						cp = new ClientPut(this, message, server, null);
						requestsByIdentifier.put(id, cp);
					} catch (IdentifierCollisionException e) {
						success = false;
					} catch (MessageInvalidException e) {
						outputHandler.queue(new ProtocolErrorMessage(e.protocolCode, false, e.getMessage(), e.ident, e.global));
						return;
					} catch (MalformedURLException e) {
						failedMessage = new ProtocolErrorMessage(ProtocolErrorMessage.FREENET_URI_PARSE_ERROR, true, e.getMessage(), id, message.global);
					}
				} else if(message.persistenceType == ClientRequest.PERSIST_FOREVER) {
					try {
						server.core.clientContext.jobRunner.queue(new DBJob() {

							@Override
							public boolean run(ObjectContainer container, ClientContext context) {
								ClientPut putter;
								try {
									putter = new ClientPut(FCPConnectionHandler.this, message, server, container);
								} catch (IdentifierCollisionException e) {
									Logger.normal(this, "Identifier collision on "+this);
									FCPMessage msg = new IdentifierCollisionMessage(id, message.global);
									outputHandler.queue(msg);
									return false;
								} catch (MessageInvalidException e) {
									outputHandler.queue(new ProtocolErrorMessage(e.protocolCode, false, e.getMessage(), e.ident, e.global));
									return false;
								} catch (MalformedURLException e) {
									outputHandler.queue(new ProtocolErrorMessage(ProtocolErrorMessage.FREENET_URI_PARSE_ERROR, true, null, id, message.global));
									return false;
								}
								try {
									putter.register(container, false);
									container.store(putter);
								} catch (IdentifierCollisionException e) {
									Logger.normal(this, "Identifier collision on "+this);
									FCPMessage msg = new IdentifierCollisionMessage(id, global);
									outputHandler.queue(msg);
									return false;
								}
								putter.start(container, context);
								container.deactivate(putter, 1);
								return true;
							}
							
						}, NativeThread.HIGH_PRIORITY-1, false);
					} catch (DatabaseDisabledException e) {
						outputHandler.queue(new ProtocolErrorMessage(ProtocolErrorMessage.PERSISTENCE_DISABLED, false, "Persistence is disabled", id, global));
					} // user wants a response soon... but doesn't want it to block the queue page etc
					return; // Don't run the start() below
				} else {
					try {
						cp = new ClientPut(this, message, server, null);
					} catch (IdentifierCollisionException e) {
						success = false;
					} catch (MessageInvalidException e) {
						outputHandler.queue(new ProtocolErrorMessage(e.protocolCode, false, e.getMessage(), e.ident, e.global));
						return;
					} catch (MalformedURLException e) {
						failedMessage = new ProtocolErrorMessage(ProtocolErrorMessage.FREENET_URI_PARSE_ERROR, true, null, id, message.global);
					}
				}
			}
			if(!success) {
				Logger.normal(this, "Identifier collision on "+this);
				failedMessage = new IdentifierCollisionMessage(id, message.global);
			}
		}
		if(message.persistenceType == ClientRequest.PERSIST_REBOOT && cp != null)
			try {
				cp.register(null, false);
			} catch (IdentifierCollisionException e) {
				failedMessage = new IdentifierCollisionMessage(id, message.global);
			}
		if(failedMessage != null) {
			if(logMINOR) Logger.minor(this, "Failed: "+failedMessage);
			outputHandler.queue(failedMessage);
			if(persistent && message.persistenceType == ClientRequest.PERSIST_FOREVER) {
				final ClientPut c = cp;
				// Run on the database thread if persistent because it will try to activate stuff...
				try {
					server.core.clientContext.jobRunner.queue(new DBJob() {

						@Override
						public boolean run(ObjectContainer container, ClientContext context) {
							if(c != null)
								c.freeData(container);
							else
								message.freeData(container);
							return true;
						}
						
					}, NativeThread.HIGH_PRIORITY-1, false);
				} catch (DatabaseDisabledException e) {
					Logger.error(this, "Unable to free data for insert because database disabled: "+e, e);
				}
			} else {
				if(cp != null)
					cp.freeData(null);
				else
					message.freeData(null);
			}
			return;
		} else {
			Logger.minor(this, "Starting "+cp);
			cp.start(null, server.core.clientContext);
		}
	}

	public void startClientPutDir(final ClientPutDirMessage message, final HashMap<String, Object> buckets, final boolean wasDiskPut) {
		if(logMINOR)
			Logger.minor(this, "Start ClientPutDir");
		final String id = message.identifier;
		final boolean global = message.global;
		ClientPutDir cp = null;
		FCPMessage failedMessage = null;
		boolean persistent = message.persistenceType != ClientRequest.PERSIST_CONNECTION;
		// We need to track non-persistent requests anyway, so we may as well check
		boolean success;
		synchronized(this) {
			if(isClosed) return;
			if(!persistent)
				success = true;
			else
				success = !requestsByIdentifier.containsKey(id);
		}
		if(success) {
			if(!persistent) {
				try {
					cp = new ClientPutDir(this, message, buckets, wasDiskPut, server, null);
					synchronized(this) {
						requestsByIdentifier.put(id, cp);
					}
				} catch (IdentifierCollisionException e) {
					success = false;
				} catch (MalformedURLException e) {
					failedMessage = new ProtocolErrorMessage(ProtocolErrorMessage.FREENET_URI_PARSE_ERROR, true, null, id, message.global);
				} catch (TooManyFilesInsertException e) {
					failedMessage = new ProtocolErrorMessage(ProtocolErrorMessage.TOO_MANY_FILES_IN_INSERT, true, null, id, message.global);
				}
				// FIXME register non-persistent requests in the constructors also, we already register persistent ones...
			} else if(message.persistenceType == ClientRequest.PERSIST_FOREVER) {
				try {
					server.core.clientContext.jobRunner.queue(new DBJob() {

						@Override
						public boolean run(ObjectContainer container, ClientContext context) {
							ClientPutDir putter;
							try {
								putter = new ClientPutDir(FCPConnectionHandler.this, message, buckets, wasDiskPut, server, container);
							} catch (IdentifierCollisionException e) {
								Logger.normal(this, "Identifier collision on "+this);
								FCPMessage msg = new IdentifierCollisionMessage(id, message.global);
								outputHandler.queue(msg);
								return false;
							} catch (MalformedURLException e) {
								outputHandler.queue(new ProtocolErrorMessage(ProtocolErrorMessage.FREENET_URI_PARSE_ERROR, true, null, id, message.global));
								return false;
							} catch (TooManyFilesInsertException e) {
								outputHandler.queue(new ProtocolErrorMessage(ProtocolErrorMessage.TOO_MANY_FILES_IN_INSERT, true, null, id, message.global));
								return false;
							}
							try {
								putter.register(container, false);
								container.store(putter);
							} catch (IdentifierCollisionException e) {
								Logger.normal(this, "Identifier collision on "+this);
								FCPMessage msg = new IdentifierCollisionMessage(id, global);
								outputHandler.queue(msg);
								return false;
							}
							putter.start(container, context);
							container.deactivate(putter, 1);
							return true;
						}
						
					}, NativeThread.HIGH_PRIORITY-1, false);
				} catch (DatabaseDisabledException e) {
					outputHandler.queue(new ProtocolErrorMessage(ProtocolErrorMessage.PERSISTENCE_DISABLED, false, "Persistence is disabled", id, global));
				} // user wants a response soon... but doesn't want it to block the queue page etc
				return; // Don't run the start() below
				
			} else {
				try {
					cp = new ClientPutDir(this, message, buckets, wasDiskPut, server, null);
				} catch (IdentifierCollisionException e) {
					success = false;
				} catch (MalformedURLException e) {
					failedMessage = new ProtocolErrorMessage(ProtocolErrorMessage.FREENET_URI_PARSE_ERROR, true, null, id, message.global);
				} catch (TooManyFilesInsertException e) {
					failedMessage = new ProtocolErrorMessage(ProtocolErrorMessage.TOO_MANY_FILES_IN_INSERT, true, null, id, message.global);
				}
			}
			if(!success) {
				Logger.normal(this, "Identifier collision on "+this);
				failedMessage = new IdentifierCollisionMessage(id, message.global);
			}
		}
		
		if(message.persistenceType == ClientRequest.PERSIST_REBOOT)
			try {
				cp.register(null, false);
			} catch (IdentifierCollisionException e) {
				failedMessage = new IdentifierCollisionMessage(id, message.global);
			}
		if(failedMessage != null) {
			// FIXME do we need to freeData???
			outputHandler.queue(failedMessage);
			if(cp != null)
				cp.cancel(null, server.core.clientContext);
			return;
		} else {
			if(logMINOR)
				Logger.minor(this, "Starting "+cp);
			cp.start(null, server.core.clientContext);
		}
	}
	
	public FCPClient getRebootClient() {
		return rebootClient;
	}

	/**
	 * @return The {@link FCPPluginClient} for the given serverPluginName (see {@link FCPPluginClient#getServerPluginName()}). Atomically creates and stores it
	 *         if there does not exist one yet. This ensures that for each {@link FCPConnectionHandler}, there can be only one {@link FCPPluginClient} for a
	 *         given serverPluginName.
	 * @throws PluginNotFoundException If the specified plugin is not loaded or does not provide an FCP server. 
	 */
	public FCPPluginClient getPluginClient(String serverPluginName) throws PluginNotFoundException {
        // The typical usage pattern of this function is that the great majority of calls will return an existing client. Creating a fresh one will typically
        // only happen at the start of a connection and then it will be re-used a lot.
        // Therefore, it would cost a lot of performance to use synchronized() and we instead use a ReadWriteLock which is optimal for such patterns.
        //
        // The double-checked locking pattern which this induces is necessary due to the fact that a read-lock cannot be upgraded to a write lock.
        // The JavaDoc of ReentrantReadWriteLock specifically recommends this pattern, so it ought to be a safe version of double-checked locking.
        
        pluginClientsByServerPluginNameLock.readLock().lock();
        try {
            FCPPluginClient peekOldClient = pluginClientsByServerPluginName.get(serverPluginName);
            
            // FIXME: Check whether its WeakReference to the server is still fine. If its pointer is null, remove the oldClient from the TreeMap and try to
            // create a fresh one because that will check whether the plugin has been reloaded meanwhile (and fail if not).
            // This is necessary because the ReferenceQueue thread which will remove the clients from the TreeMap once their WeakReference is null might take
            // a few minutes to execute under low memory pressure (I have observed that while debugging).  Also, when implementing that thread (it is not
            // implemented yet), make sure to not treat it as an error if the WeakReference has been removed from the TreeMap already, because that is what
            // we will do here once this FIXME is resolved.
            if(peekOldClient != null)
                return peekOldClient;
        } finally {
            // A read-lock cannot be upgraded to a write-lock so we must always unlock
            pluginClientsByServerPluginNameLock.readLock().unlock();
        }

        pluginClientsByServerPluginNameLock.writeLock().lock();
        try {
            // Re-check whether there is an existing client since we had to re-acquire the lock meanwhile.
            FCPPluginClient oldClient = pluginClientsByServerPluginName.get(serverPluginName);
            
            if(oldClient != null) 
                return oldClient;

            FCPPluginClient newClient = server.createClientForNetworkedFCP(serverPluginName, this);
            pluginClientsByServerPluginName.put(serverPluginName, newClient);
            
            return newClient;
        } finally {
            pluginClientsByServerPluginNameLock.writeLock().unlock();    
        }
	}

	public FCPClient getForeverClient(ObjectContainer container) {
		synchronized(this) {
			if(foreverClient == null) {
				foreverClient = createForeverClient(clientName, container);
			}
			container.activate(foreverClient, 1);
			foreverClient.init(container);
			return foreverClient;
		}
	}

	public void finishedClientRequest(ClientRequest get) {
		synchronized(this) {
			requestsByIdentifier.remove(get.getIdentifier());
		}
	}

	public boolean isGlobalSubscribed() {
		return rebootClient.watchGlobal;
	}

	public boolean hasFullAccess() {
		return server.allowedHostsFullAccess.allowed(sock.getInetAddress());
	}

	/**
	 * That method ought to be called before any DirectDiskAccess operation is performed by the node
	 * @param filename
	 * @param writeRequest : Are willing to write or to read ?
	 * @return boolean : allowed or not
	 */
	protected boolean allowDDAFrom(File filename, boolean writeRequest) {
		String parentDirectory = FileUtil.getCanonicalFile(filename).getParent();
		DirectoryAccess da = null;
		
		synchronized (checkedDirectories) {
				da = checkedDirectories.get(parentDirectory);
		}
		
		if(logMINOR)
			Logger.minor(this, "Checking DDA: "+da+" for "+parentDirectory);
		
		if(writeRequest)
			return (da == null ? server.isDownloadDDAAlwaysAllowed() : da.canWrite);
		else
			return (da == null ? server.isUploadDDAAlwaysAllowed() : da.canRead);
	}
	
	/**
	 * SHOULD BE CALLED ONLY FROM TestDDAComplete!
	 * @param path
	 * @param read
	 * @param write
	 */
	protected void registerTestDDAResult(String path, boolean read, boolean write) {
		DirectoryAccess da = new DirectoryAccess(read, write);
		
		synchronized (checkedDirectories) {
				checkedDirectories.put(path, da);
		}
		
		if(logMINOR)
			Logger.minor(this, "DDA: read="+read+" write="+write+" for "+path);
	}
	
	/**
	 * Return a DDACheckJob : the one we created and have enqueued
	 * @param path
	 * @param read : is Read access requested ?
	 * @param write : is Write access requested ?
	 * @return
	 * @throws IllegalArgumentException
	 * 
	 * FIXME: Maybe we need to enqueue a PS job to delete the created file after something like ... 5 mins ?
	 */
	protected DDACheckJob enqueueDDACheck(String path, boolean read, boolean write) throws IllegalArgumentException {
		File directory = FileUtil.getCanonicalFile(new File(path));
		if(!directory.exists() || !directory.isDirectory())
			throw new IllegalArgumentException("The specified path isn't a directory! or doesn't exist or the node doesn't have access to it!");
		
		// See #1856
		DDACheckJob job = null;
		synchronized (inTestDirectories) {
			job = inTestDirectories.get(directory);
		}
		if(job != null)
			throw new IllegalArgumentException("There is already a TestDDA going on for that directory!");
		
		File writeFile = (write ? new File(path, "DDACheck-" + server.node.fastWeakRandom.nextInt() + ".tmp") : null);
		File readFile = null;
		if(read) {
			try {
				readFile = File.createTempFile("DDACheck-", ".tmp", directory);
				readFile.deleteOnExit();
			} catch (IOException e) {
				// Now we know it: we can't write there ;)
				readFile = null;
			}
		}

		DDACheckJob result = new DDACheckJob(server.node.fastWeakRandom, directory, readFile, writeFile);
		synchronized (inTestDirectories) {
			inTestDirectories.put(directory, result);
		}
		
		if(read && (readFile != null) && readFile.canWrite()){ 
			// We don't want to attempt to write before: in case an IOException is raised, we want to inform the
			// client somehow that the node can't write there... And setting readFile to null means we won't inform
			// it on the status (as if it hadn't requested us to do the test).
			FileOutputStream fos = null;
			BufferedOutputStream bos = null;
			try {
				fos = new FileOutputStream(result.readFilename);
				bos = new BufferedOutputStream(fos);
				bos.write(result.readContent.getBytes("UTF-8"));
				bos.flush();
			} catch (IOException e) {
				Logger.error(this, "Got a IOE while creating the file (" + readFile.toString() + " ! " + e.getMessage());
			} finally {
				Closer.close(bos);
				Closer.close(fos);
			}
		}
		
		return result;
	}
	
	/**
	 * Return a DDACheckJob or null if not found
	 * @param path
	 * @return the DDACheckJob
	 * @throws IllegalArgumentException
	 */
	protected DDACheckJob popDDACheck(String path) throws IllegalArgumentException {
		File directory = FileUtil.getCanonicalFile(new File(path));
		if(!directory.exists() || !directory.isDirectory())
			throw new IllegalArgumentException("The specified path isn't a directory! or doesn't exist or the node doesn't have access to it!");
		
		synchronized (inTestDirectories) {
			return inTestDirectories.remove(directory);
		}
	}
	
	/**
	 * Delete the files we have created using DDATest
	 * called by FCPClient.onDisconnect(handler)
	 */
	protected void freeDDAJobs(){
		synchronized (inTestDirectories) {
			for(DDACheckJob job: inTestDirectories.values()) {
				if (job.readFilename != null)
					job.readFilename.delete();
			}
		}
	}

	public ClientRequest removeRequestByIdentifier(String identifier, boolean kill) {
		ClientRequest req;
		synchronized(this) {
			req = requestsByIdentifier.remove(identifier);
		}
		if(req != null) {
			if(kill)
				req.cancel(null, server.core.clientContext);
			req.requestWasRemoved(null, server.core.clientContext);
		}
		return req;
	}
	
	ClientRequest getRebootRequest(boolean global, FCPConnectionHandler handler, String identifier) {
		if(global)
			return handler.server.globalRebootClient.getRequest(identifier, null);
		else
			return handler.getRebootClient().getRequest(identifier, null);
	}
	
	ClientRequest getForeverRequest(boolean global, FCPConnectionHandler handler, String identifier, ObjectContainer container) {
		if(global)
			return handler.server.globalForeverClient.getRequest(identifier, container);
		else
			return handler.getForeverClient(container).getRequest(identifier, container);
	}
	
	ClientRequest removePersistentRebootRequest(boolean global, String identifier) throws MessageInvalidException {
		FCPClient client =
			global ? server.globalRebootClient :
			getRebootClient();
		ClientRequest req = client.getRequest(identifier, null);
		if(req != null) {
			client.removeByIdentifier(identifier, true, server, null, server.core.clientContext);
		}
		return req;
	}
	
	ClientRequest removePersistentForeverRequest(boolean global, String identifier, ObjectContainer container) throws MessageInvalidException {
		FCPClient client =
			global ? server.globalForeverClient :
			getForeverClient(container);
		container.activate(client, 1);
		ClientRequest req = client.getRequest(identifier, container);
		if(req != null) {
			client.removeByIdentifier(identifier, true, server, container, server.core.clientContext);
		}
		if(!global)
			container.deactivate(client, 1);
		return req;
	}
	
	public boolean objectCanNew(ObjectContainer container) {
		Logger.error(this, "Not storing FCPConnectionHandler in database", new Exception("error"));
		return false;
	}

	public synchronized void addUSKSubscription(String identifier, SubscribeUSK subscribeUSK) throws IdentifierCollisionException {
		if(uskSubscriptions.containsKey(identifier)) throw new IdentifierCollisionException();
		uskSubscriptions.put(identifier, subscribeUSK);
	}

	public void unsubscribeUSK(String identifier) throws MessageInvalidException {
		SubscribeUSK sub;
		synchronized(this) {
			sub = uskSubscriptions.remove(identifier);
			if(sub == null) throw new MessageInvalidException(ProtocolErrorMessage.NO_SUCH_IDENTIFIER, "No such identifier unsubscribing", identifier, false);
		}
		sub.unsubscribe();
	}

	public RequestClient connectionRequestClient(boolean realTime) {
		if(realTime)
			return connectionRequestClientRT;
		else
			return connectionRequestClientBulk;
	}

}
