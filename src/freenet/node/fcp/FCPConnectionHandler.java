package freenet.node.fcp;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.Socket;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;

import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.api.BucketFactory;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;

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
	public final FCPConnectionOutputHandler outputHandler;
	private boolean isClosed;
	private boolean inputClosed;
	private boolean outputClosed;
	private String clientName;
	private FCPClient client;
	final BucketFactory bf;
	final HashMap<String, ClientRequest> requestsByIdentifier;
	protected final String connectionIdentifier;
	static boolean logMINOR;

	// We are confident that the given client can access those
	private final HashMap<String, DirectoryAccess> checkedDirectories = new HashMap<String, DirectoryAccess>();
	// DDACheckJobs in flight
	private final HashMap<File, DDACheckJob> inTestDirectories = new HashMap<File, DDACheckJob>();
	
	public FCPConnectionHandler(Socket s, FCPServer server) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.sock = s;
		this.server = server;
		isClosed = false;
		this.bf = server.core.tempBucketFactory;
		requestsByIdentifier = new HashMap<String, ClientRequest>();
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

	public void close() {
		ClientRequest[] requests;
		if(client != null)
			client.onLostConnection(this);
		synchronized(this) {
			isClosed = true;
			requests = new ClientRequest[requestsByIdentifier.size()];
			requests = requestsByIdentifier.values().toArray(requests);
		}
		for(int i=0;i<requests.length;i++)
			requests[i].onLostConnection();
		if((client != null) && !client.hasPersistentRequests())
			server.unregisterClient(client);
		outputHandler.onClosed();
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

	public void setClientName(String name) {
		this.clientName = name;
		client = server.registerClient(name, server.core, this);
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Set client name: "+name);
	}
	
	public String getClientName() {
		return clientName;
	}

	public void startClientGet(ClientGetMessage message) {
		String id = message.identifier;
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
					cg = new ClientGet(this, message);
					if(!persistent)
						requestsByIdentifier.put(id, cg);
				} catch (IdentifierCollisionException e) {
					success = false;
				} catch (MessageInvalidException e) {
					outputHandler.queue(new ProtocolErrorMessage(e.protocolCode, false, e.getMessage(), e.ident, e.global));
					return;
				}
			}
		}
		if(!success) {
			Logger.normal(this, "Identifier collision on "+this);
			FCPMessage msg = new IdentifierCollisionMessage(id, message.global);
			outputHandler.queue(msg);
			return;
		} else {
			// Register before starting, because it may complete immediately, and if it does,
			// we may end up with it not being removable because it wasn't registered!
			if(cg.isPersistent()) {
				if(cg.isPersistentForever())
					server.forceStorePersistentRequests();
			}
			cg.start();
		}
	}

	public void startClientPut(ClientPutMessage message) {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Starting insert ID=\""+message.identifier+ '"');
		String id = message.identifier;
		ClientPut cp = null;
		boolean persistent = message.persistenceType != ClientRequest.PERSIST_CONNECTION;
		FCPMessage failedMessage = null;
		synchronized(this) {
			boolean success;
			if(isClosed) return;
			// We need to track non-persistent requests anyway, so we may as well check
			if(persistent)
				success = true;
			else
				success = !requestsByIdentifier.containsKey(id);
			if(success) {
				try {
					cp = new ClientPut(this, message);
				} catch (IdentifierCollisionException e) {
					success = false;
				} catch (MessageInvalidException e) {
					outputHandler.queue(new ProtocolErrorMessage(e.protocolCode, false, e.getMessage(), e.ident, e.global));
					return;
				} catch (MalformedURLException e) {
					failedMessage = new ProtocolErrorMessage(ProtocolErrorMessage.FREENET_URI_PARSE_ERROR, true, null, id, message.global);
				}
				if(!persistent)
					requestsByIdentifier.put(id, cp);
			}
			if(!success) {
				Logger.normal(this, "Identifier collision on "+this);
				failedMessage = new IdentifierCollisionMessage(id, message.global);
			}
		}
		if(failedMessage != null) {
			outputHandler.queue(failedMessage);
			return;
		} else {
			Logger.minor(this, "Starting "+cp);
			// Register before starting, because it may complete immediately, and if it does,
			// we may end up with it not being removable because it wasn't registered!
			if(cp.isPersistent()) {
				if(cp.isPersistentForever())
					server.forceStorePersistentRequests();
			}
			cp.start();
		}
	}

	public void startClientPutDir(ClientPutDirMessage message, HashMap<String, Object> buckets, boolean wasDiskPut) {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Start ClientPutDir");
		String id = message.identifier;
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
			try {
				cp = new ClientPutDir(this, message, buckets, wasDiskPut);
			} catch (IdentifierCollisionException e) {
				success = false;
			} catch (MalformedURLException e) {
				failedMessage = new ProtocolErrorMessage(ProtocolErrorMessage.FREENET_URI_PARSE_ERROR, true, null, id, message.global);
			}
			if(!persistent) {
				synchronized(this) {
					requestsByIdentifier.put(id, cp);
				}
				// FIXME register non-persistent requests in the constructors also, we already register persistent ones...
			}
			if(!success) {
				Logger.normal(this, "Identifier collision on "+this);
				failedMessage = new IdentifierCollisionMessage(id, message.global);
			}
		}
		if(failedMessage != null) {
			outputHandler.queue(failedMessage);
			if(cp != null)
				cp.cancel();
			return;
		} else {
			// Register before starting, because it may complete immediately, and if it does,
			// we may end up with it not being removable because it wasn't registered!
			if(cp.isPersistent()) {
				if(cp.isPersistentForever())
					server.forceStorePersistentRequests();
			}
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "Starting "+cp);
			cp.start();
		}
	}
	
	public FCPClient getClient() {
		return client;
	}

	public void finishedClientRequest(ClientRequest get) {
		synchronized(this) {
			requestsByIdentifier.remove(get.getIdentifier());
		}
	}

	public boolean isGlobalSubscribed() {
		return client.watchGlobal;
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
			Iterator<File> it = inTestDirectories.keySet().iterator();
			while(it.hasNext()) {
				DDACheckJob job = inTestDirectories.get(it.next());
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
			req.requestWasRemoved();
			if(kill)
				req.cancel();
		}
		return req;
	}
}
