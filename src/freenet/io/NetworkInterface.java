/*
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */

package freenet.io;

import java.io.Closeable;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.StringTokenizer;

import org.tanukisoftware.wrapper.WrapperManager;

import freenet.io.AddressIdentifier.AddressType;
import freenet.support.Executor;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/**
 * Replacement for {@link ServerSocket} that can handle multiple bind addresses
 * and allows IP address level filtering.
 * 
 * @author David Roden &lt;droden@gmail.com&gt;
 * @version $Id$
 */
public class NetworkInterface implements Closeable {
    
	private static volatile boolean logMINOR;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
            @Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
        public static final String DEFAULT_BIND_TO = "127.0.0.1,0:0:0:0:0:0:0:1";
        
	/** Object for synchronisation purpose. */
	protected final Object syncObject = new Object();

	/** Acceptors created by this interface. */
	private final List<Acceptor>  acceptors = new ArrayList<Acceptor>();

	/** Queue of accepted client connections. */
	protected final Queue<Socket> acceptedSockets = new ArrayDeque<Socket>();
	
	/** AllowedHosts structure */
	protected final AllowedHosts allowedHosts;

	/** The timeout set by {@link #setSoTimeout(int)}. */
	private int timeout = 0;

	/** The port to bind to. */
	private final int port;

	/** The number of running acceptors. */
	private int runningAcceptors = 0;
	
	private volatile boolean shutdown = false;
	
	private final Executor executor;

	// FIXME make configurable
	static final int maxQueueLength = 100;

	public static NetworkInterface create(int port, String bindTo, String allowedHosts, Executor executor, boolean ignoreUnbindableIP6) throws IOException {
		NetworkInterface iface = new NetworkInterface(port, allowedHosts, executor);
		try {
			iface.setBindTo(bindTo, ignoreUnbindableIP6);
		} catch (IOException e) {
			try {
				iface.close();
			} catch (IOException e1) {
				Logger.error(NetworkInterface.class, "Caught "+e1+" closing after catching "+e+" binding while constructing", e1);
				// Ignore
			}
			throw e;
		}
		return iface;
	}
	
	/**
	 * Creates a new network interface that can bind to several addresses and
	 * allows connection filtering on IP address level.
	 * 
	 * @param bindTo
	 *            A comma-separated list of addresses to bind to
	 * @param allowedHosts
	 *            A comma-separated list of allowed addresses
	 */
	protected NetworkInterface(int port, String allowedHosts, Executor executor) throws IOException {
		this.port = port;
		this.allowedHosts = new AllowedHosts(allowedHosts);
		this.executor = executor;
	}

	protected ServerSocket createServerSocket() throws IOException {
		return new ServerSocket();
	}
	
	/**
	 * Sets the list of IP address this network interface binds to.
	 * 
	 * @param bindTo
	 *            A comma-separated list of IP address to bind to
	 */
	public void setBindTo(String bindTo, boolean ignoreUnbindableIP6) throws IOException {
                if(bindTo == null || bindTo.equals("")) bindTo = NetworkInterface.DEFAULT_BIND_TO;
		StringTokenizer bindToTokens = new StringTokenizer(bindTo, ",");
		List<String> bindToTokenList = new ArrayList<String>();
		while (bindToTokens.hasMoreTokens()) {
			bindToTokenList.add(bindToTokens.nextToken().trim());
		}
		/* stop the old acceptors. */
		for (int acceptorIndex = 0, acceptorCount = acceptors.size(); acceptorIndex < acceptorCount; acceptorIndex++) {
			Acceptor acceptor = acceptors.get(acceptorIndex);
			try {
				acceptor.close();
			} catch (IOException e) {
				/* swallow exception. */
			}
		}
		synchronized (syncObject) {
			while (runningAcceptors > 0) {
				try {
					syncObject.wait();
				} catch (InterruptedException e) {
				}
			}
		}
		acceptors.clear();
		for (int serverSocketIndex = 0; serverSocketIndex < bindToTokenList.size(); serverSocketIndex++) {
			ServerSocket serverSocket = createServerSocket();
			InetSocketAddress addr = null;
			try {
				addr = new InetSocketAddress(bindToTokenList.get(serverSocketIndex), port);
				serverSocket.setReuseAddress(true);
				serverSocket.bind(addr);
			} catch (SocketException e) {
				if(ignoreUnbindableIP6 && addr != null && addr.getAddress() instanceof Inet6Address)
					continue;
				else
					throw e;
			}
			Acceptor acceptor = new Acceptor(serverSocket);
			acceptors.add(acceptor);
		}
		setSoTimeout(timeout);
		synchronized (syncObject) {
			for (Acceptor acceptor : this.acceptors) {
				executor.execute(acceptor, "Network Interface Acceptor for "+acceptor.serverSocket);
			}
			syncObject.notifyAll();
		}
	}

	public void setAllowedHosts(String allowedHosts) {
		this.allowedHosts.setAllowedHosts(allowedHosts);
	}

	/**
	 * Sets the SO_TIMEOUT value on the server sockets.
	 * 
	 * @param timeout
	 *            The timeout in milliseconds, <code>0</code> to disable
	 * @throws SocketException
	 *             if the SO_TIMEOUT value can not be set
	 * @see ServerSocket#setSoTimeout(int)
	 */
	public void setSoTimeout(int timeout) throws SocketException {
		for (Acceptor acceptor : acceptors) {
			acceptor.setSoTimeout(timeout);
		}
		this.timeout = timeout;
	}

	/**
	 * Waits for a connection. If a timeout has been set using
	 * {@link #setSoTimeout(int)} and no connection is established this method
	 * will return after the specified timeout has been expired, throwing a
	 * {@link SocketTimeoutException}. If no timeout has been set this method
	 * will wait until a connection has been established.
	 * 
	 * @return The socket that is connected to the client or null
     * if the timeout has expired waiting for a connection
	 */
	public Socket accept() {
		synchronized (syncObject) {
			Socket socket;
			while ((socket = acceptedSockets.poll()) == null ) {
				if (shutdown)
					return null;
				if (WrapperManager.hasShutdownHookBeenTriggered())
					return null;
				if (acceptors.size() == 0) {
					return null;
				}
				try {
					syncObject.wait(timeout);
				} catch (InterruptedException ie1) {
				}
				if (timeout > 0) {
					socket = acceptedSockets.poll();
					break;
				}
			}
			return socket;
		}
	}

	/**
	 * Closes this interface and all underlying server sockets.
	 * 
	 * @throws IOException
	 *             if an I/O exception occurs
	 * @see ServerSocket#close()
	 */
	@Override
	public void close() throws IOException {
		IOException exception = null;
		shutdown = true;
		for (Acceptor acceptor : acceptors) {
			try {
				acceptor.close();
			} catch (IOException ioe1) {
				exception = ioe1;
			}
		}
		synchronized (syncObject) {
			syncObject.notifyAll();
		}
		if (exception != null) {
			throw exception;
		}
	}

	/**
	 * Gets called by an acceptor if it has stopped.
	 */
	private void acceptorStopped() {
		synchronized (syncObject) {
			runningAcceptors--;
			syncObject.notifyAll();
		}
	}

	/**
	 * Wrapper around a {@link ServerSocket} that checks whether the incoming
	 * connection is allowed.
	 * 
	 * @author David Roden &lt;droden@gmail.com&gt;
	 * @version $Id$
	 */
	private class Acceptor implements Runnable {

		/** The {@link ServerSocket} to listen on. */
		private final ServerSocket serverSocket;

		/** Whether this acceptor has been closed. */
		private boolean closed = false;

		/**
		 * Creates a new acceptor on the specified server socket.
		 * 
		 * @param serverSocket
		 *            The server socket to listen on
		 */
		public Acceptor(ServerSocket serverSocket) {
			this.serverSocket = serverSocket;
		}

		/**
		 * Sets the SO_TIMEOUT value on this acceptor's server socket.
		 * 
		 * @param timeout
		 *            The timeout in milliseconds, or <code>0</code> to
		 *            disable
		 * @throws SocketException
		 *             if the SO_TIMEOUT value can not be set
		 * @see ServerSocket#setSoTimeout(int)
		 */
		public void setSoTimeout(int timeout) throws SocketException {
			serverSocket.setSoTimeout(timeout);
		}

		/**
		 * Closes this acceptor and the underlying server socket.
		 * 
		 * @throws IOException
		 *             if an I/O exception occurs
		 * @see ServerSocket#close()
		 */
		public void close() throws IOException {
			closed = true;
			serverSocket.close();
		}

		/**
		 * Main method that accepts connections and checks the address against
		 * the list of allowed hosts.
		 * 
		 * @see NetworkInterface#allowedHosts
		 */
		@Override
		public void run() {
		    freenet.support.Logger.OSThread.logPID(this);
			while (!closed) {
				try {
					Socket clientSocket = serverSocket.accept();
					InetAddress clientAddress = clientSocket.getInetAddress();
					if(logMINOR)
						Logger.minor(Acceptor.class, "Connection from " + clientAddress);
					
					AddressType clientAddressType = AddressIdentifier.getAddressType(clientAddress.getHostAddress());

					/* check if the ip address is allowed */
					if (allowedHosts.allowed(clientAddressType, clientAddress) && acceptedSockets.size() <= maxQueueLength) {
						synchronized (syncObject) {
							acceptedSockets.add(clientSocket);
							syncObject.notifyAll();
						}
					} else {
						try {
							clientSocket.close();
						} catch (IOException ioe1) {
						}
						Logger.normal(Acceptor.class, "Denied connection to " + clientAddress);
					}
				} catch (SocketTimeoutException ste1) {
					if(logMINOR)
						Logger.minor(this, "Timeout");
				} catch (IOException ioe1) {
					if(logMINOR)
						Logger.minor(this, "Caught " + ioe1);
				}
			}
			NetworkInterface.this.acceptorStopped();
		}

	}

	public String getAllowedHosts() {
		return allowedHosts.getAllowedHosts();
	}

	public boolean isBound() {
		return this.acceptors.size() != 0;
	}

	public void waitBound() throws InterruptedException {
		synchronized(syncObject) {
			if(isBound()) return;
			while (true) {
				Logger.error(this, "Network interface isn't bound, waiting");
				syncObject.wait();
				if(isBound()) {
					Logger.error(this, "Finished waiting, network interface is now bound");
					return;
				}
			}
			
		}
	}

}
