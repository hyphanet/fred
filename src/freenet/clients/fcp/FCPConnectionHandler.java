package freenet.clients.fcp;

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

import freenet.client.async.ClientContext;
import freenet.client.async.PersistenceDisabledException;
import freenet.client.async.PersistentJob;
import freenet.client.async.TooManyFilesInsertException;
import freenet.clients.fcp.ClientRequest.Persistence;
import freenet.node.RequestClient;
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
	private PersistentRequestClient rebootClient;
	private PersistentRequestClient foreverClient;
	final BucketFactory bf;
	final HashMap<String, ClientRequest> requestsByIdentifier;
	public final String connectionIdentifier;
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
			req.onLostConnection(server.core.clientContext);
		for(SubscribeUSK sub : uskSubscriptions2)
			sub.unsubscribe();
		if(!dupe) {
		    try {
		        server.core.clientContext.jobRunner.queue(new PersistentJob() {
		            
		            @Override
		            public boolean run(ClientContext context) {
		                if((rebootClient != null) && !rebootClient.hasPersistentRequests())
		                    server.unregisterClient(rebootClient);
		                if(foreverClient != null) {
		                    if(!foreverClient.hasPersistentRequests())
		                        server.unregisterClient(foreverClient);
		                }
		                return false;
		            }
		            
		        }, NativeThread.NORM_PRIORITY);
		    } catch (PersistenceDisabledException e) {
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
		rebootClient.queuePendingMessagesOnConnectionRestartAsync(outputHandler, server.core.clientContext);
		// Create foreverClient lazily. Everything that needs it (especially creating ClientGet's etc) runs on a database job.
		if(logMINOR)
			Logger.minor(this, "Set client name: "+name);
		PersistentRequestClient client = server.getForeverClient(name, server.core, this);
		if(client != null) {
		    synchronized(this) {
		        foreverClient = client;
		    }
            foreverClient.queuePendingMessagesOnConnectionRestartAsync(outputHandler, server.core.clientContext);
		}
	}
	
	protected PersistentRequestClient createForeverClient(String name) {
		synchronized(FCPConnectionHandler.this) {
			if(foreverClient != null) return foreverClient;
		}
		PersistentRequestClient client = server.registerForeverClient(name, server.core, FCPConnectionHandler.this);
		synchronized(FCPConnectionHandler.this) {
			foreverClient = client;
			FCPConnectionHandler.this.notifyAll();
		}
		client.queuePendingMessagesOnConnectionRestartAsync(outputHandler, server.core.clientContext);
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
		boolean persistent = message.persistence != Persistence.CONNECTION;
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
						cg = new ClientGet(this, message, server.core);
						requestsByIdentifier.put(id, cg);
					} else if(message.persistence == Persistence.FOREVER) {
					    try {
					        server.core.clientContext.jobRunner.queue(new PersistentJob() {
					            
					            @Override
					            public boolean run(ClientContext context) {
					                ClientGet getter;
					                try {
					                    getter = new ClientGet(FCPConnectionHandler.this, message, server.core);
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
					                    getter.register(false);
					                } catch (IdentifierCollisionException e) {
					                    Logger.normal(this, "Identifier collision on "+this);
					                    FCPMessage msg = new IdentifierCollisionMessage(id, global);
					                    outputHandler.queue(msg);
					                    return false;
					                }
					                getter.start(context);
					                return true;
					            }
					            
					        }, NativeThread.HIGH_PRIORITY-1);
					    } catch (PersistenceDisabledException e) {
					        outputHandler.queue(new ProtocolErrorMessage(ProtocolErrorMessage.PERSISTENCE_DISABLED, false, "Persistence is disabled", id, global));
					        return;
					    }
						return; // Don't run the start() below
					} else {
						cg = new ClientGet(this, message, server.core);
					}
				} catch (IdentifierCollisionException e) {
					success = false;
				} catch (MessageInvalidException e) {
					outputHandler.queue(new ProtocolErrorMessage(e.protocolCode, false, e.getMessage(), e.ident, e.global));
					return;
				}
			}
		}
		if(message.persistence == Persistence.REBOOT)
			try {
				cg.register(false);
			} catch (IdentifierCollisionException e) {
				success = false;
			}
		if(!success) {
			Logger.normal(this, "Identifier collision on "+this);
			FCPMessage msg = new IdentifierCollisionMessage(id, message.global);
			outputHandler.queue(msg);
			return;
		} else {
			cg.start(server.core.clientContext);
		}
	}

	public void startClientPut(final ClientPutMessage message) {
		if(logMINOR)
			Logger.minor(this, "Starting insert ID=\""+message.identifier+ '"');
		final String id = message.identifier;
		final boolean global = message.global;
		ClientPut cp = null;
		boolean persistent = message.persistence != Persistence.CONNECTION;
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
						cp = new ClientPut(this, message, server);
						requestsByIdentifier.put(id, cp);
					} catch (IdentifierCollisionException e) {
						success = false;
					} catch (MessageInvalidException e) {
						outputHandler.queue(new ProtocolErrorMessage(e.protocolCode, false, e.getMessage(), e.ident, e.global));
						return;
					} catch (MalformedURLException e) {
						failedMessage = new ProtocolErrorMessage(ProtocolErrorMessage.FREENET_URI_PARSE_ERROR, true, e.getMessage(), id, message.global);
					} catch (IOException e) {
					    failedMessage = new ProtocolErrorMessage(ProtocolErrorMessage.IO_ERROR, true, e.getMessage(), id, message.global);
                    }
				} else if(message.persistence == Persistence.FOREVER) {
				    try {
				        server.core.clientContext.jobRunner.queue(new PersistentJob() {
				            
				            @Override
				            public boolean run(ClientContext context) {
				                ClientPut putter;
				                try {
				                    putter = new ClientPut(FCPConnectionHandler.this, message, server);
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
				                } catch (IOException e) {
                                    outputHandler.queue(new ProtocolErrorMessage(ProtocolErrorMessage.IO_ERROR, true, null, id, message.global));
                                    return false;
                                }
				                try {
				                    putter.register(false);
				                } catch (IdentifierCollisionException e) {
				                    Logger.normal(this, "Identifier collision on "+this);
				                    FCPMessage msg = new IdentifierCollisionMessage(id, global);
				                    outputHandler.queue(msg);
				                    return false;
				                }
				                putter.start(context);
				                return true;
				            }
				        
				        }, NativeThread.HIGH_PRIORITY-1);
				    } catch (PersistenceDisabledException e) {
				        outputHandler.queue(new ProtocolErrorMessage(ProtocolErrorMessage.PERSISTENCE_DISABLED, false, "Persistence is disabled", id, global));
				    }
					return; // Don't run the start() below
				} else {
					try {
						cp = new ClientPut(this, message, server);
					} catch (IdentifierCollisionException e) {
						success = false;
					} catch (MessageInvalidException e) {
						outputHandler.queue(new ProtocolErrorMessage(e.protocolCode, false, e.getMessage(), e.ident, e.global));
						return;
					} catch (MalformedURLException e) {
						failedMessage = new ProtocolErrorMessage(ProtocolErrorMessage.FREENET_URI_PARSE_ERROR, true, null, id, message.global);
					} catch (IOException e) {
                        failedMessage = new ProtocolErrorMessage(ProtocolErrorMessage.IO_ERROR, true, null, id, message.global);
                    }
				}
			}
			if(!success) {
				Logger.normal(this, "Identifier collision on "+this);
				failedMessage = new IdentifierCollisionMessage(id, message.global);
			}
		}
		if(message.persistence == Persistence.REBOOT && cp != null)
			try {
				cp.register(false);
			} catch (IdentifierCollisionException e) {
				failedMessage = new IdentifierCollisionMessage(id, message.global);
			}
		if(failedMessage != null) {
			if(logMINOR) Logger.minor(this, "Failed: "+failedMessage);
			outputHandler.queue(failedMessage);
			if(cp != null)
			    cp.freeData();
			else
			    message.freeData();
			return;
		} else {
			Logger.minor(this, "Starting "+cp);
			cp.start(server.core.clientContext);
		}
	}

	public void startClientPutDir(final ClientPutDirMessage message, final HashMap<String, Object> buckets, final boolean wasDiskPut) {
		if(logMINOR)
			Logger.minor(this, "Start ClientPutDir");
		final String id = message.identifier;
		final boolean global = message.global;
		ClientPutDir cp = null;
		FCPMessage failedMessage = null;
		boolean persistent = message.persistence != Persistence.CONNECTION;
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
					cp = new ClientPutDir(this, message, buckets, wasDiskPut, server);
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
			} else if(message.persistence == Persistence.FOREVER) {
			    try {
			        server.core.clientContext.jobRunner.queue(new PersistentJob() {
			            
			            @Override
			            public boolean run(ClientContext context) {
			                ClientPutDir putter;
			                try {
			                    putter = new ClientPutDir(FCPConnectionHandler.this, message, buckets, wasDiskPut, server);
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
			                    putter.register(false);
			                } catch (IdentifierCollisionException e) {
			                    Logger.normal(this, "Identifier collision on "+this);
			                    FCPMessage msg = new IdentifierCollisionMessage(id, global);
			                    outputHandler.queue(msg);
			                    return false;
			                }
			                putter.start(context);
			                return true;
			            }
			            
			        }, NativeThread.HIGH_PRIORITY-1);
			    } catch (PersistenceDisabledException e) {
			        outputHandler.queue(new ProtocolErrorMessage(ProtocolErrorMessage.PERSISTENCE_DISABLED, false, "Persistence is disabled", id, global));
			    }
				return; // Don't run the start() below
				
			} else {
				try {
					cp = new ClientPutDir(this, message, buckets, wasDiskPut, server);
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
		
		if(message.persistence == Persistence.REBOOT)
			try {
				cp.register(false);
			} catch (IdentifierCollisionException e) {
				failedMessage = new IdentifierCollisionMessage(id, message.global);
			}
		if(failedMessage != null) {
			// FIXME do we need to freeData???
			outputHandler.queue(failedMessage);
			if(cp != null)
				cp.cancel(server.core.clientContext);
			return;
		} else {
			if(logMINOR)
				Logger.minor(this, "Starting "+cp);
			cp.start(server.core.clientContext);
		}
	}
	
	public PersistentRequestClient getRebootClient() {
		return rebootClient;
	}

	public PersistentRequestClient getForeverClient() {
		synchronized(this) {
			if(foreverClient == null) {
				foreverClient = createForeverClient(clientName);
			}
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
	 * called by PersistentRequestClient.onDisconnect(handler)
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
				req.cancel(server.core.clientContext);
			req.requestWasRemoved(server.core.clientContext);
		}
		return req;
	}
	
	ClientRequest getRebootRequest(boolean global, FCPConnectionHandler handler, String identifier) {
		if(global)
			return handler.server.globalRebootClient.getRequest(identifier);
		else
			return handler.getRebootClient().getRequest(identifier);
	}
	
	ClientRequest getForeverRequest(boolean global, FCPConnectionHandler handler, String identifier) {
		if(global)
			return handler.server.globalForeverClient.getRequest(identifier);
		else
			return handler.getForeverClient().getRequest(identifier);
	}
	
	ClientRequest removePersistentRebootRequest(boolean global, String identifier) throws MessageInvalidException {
		PersistentRequestClient client =
			global ? server.globalRebootClient :
			getRebootClient();
		ClientRequest req = client.getRequest(identifier);
		if(req != null) {
			client.removeByIdentifier(identifier, true, server, server.core.clientContext);
		}
		return req;
	}
	
	ClientRequest removePersistentForeverRequest(boolean global, String identifier) throws MessageInvalidException {
		PersistentRequestClient client =
			global ? server.globalForeverClient :
			getForeverClient();
		ClientRequest req = client.getRequest(identifier);
		if(req != null) {
			client.removeByIdentifier(identifier, true, server, server.core.clientContext);
		}
		return req;
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
