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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import freenet.io.AddressIdentifier.AddressType;
import freenet.support.Logger;

/**
 * Replacement for {@link ServerSocket} that can handle multiple bind addresses
 * and allows IP address level filtering.
 * 
 * @author David Roden &lt;droden@gmail.com&gt;
 * @version $Id$
 */
public class NetworkInterface {

	/** Object for synchronisation purpose. */
	protected final Object syncObject = new Object();

	/** Acceptors created by this interface. */
	private final List/* <Acceptor> */acceptors = new ArrayList();

	/** List of allowed hosts. */
	protected final List/* <String> */allowedHosts = new ArrayList();

	/** Maps allowed hosts to address matchers, if possible. */
	protected final Map/* <String, AddressMatcher> */addressMatchers = new HashMap();

	/** Queue of accepted client connections. */
	protected final List/* <Socket> */acceptedSockets = new ArrayList();

	/** The timeout set by {@link #setSoTimeout(int)}. */
	private int timeout = 0;

	/** The port to bind to. */
	private final int port;

	/** The number of running acceptors. */
	private int runningAcceptors = 0;

	/**
	 * Creates a new network interface that can bind to several addresses and
	 * allows connection filtering on IP address level.
	 * 
	 * @param bindTo
	 *            A comma-separated list of addresses to bind to
	 * @param allowedHosts
	 *            A comma-separated list of allowed addresses
	 */
	public NetworkInterface(int port, String bindTo, String allowedHosts) throws IOException {
		this.port = port;
		setBindTo(bindTo);
		setAllowedHosts(allowedHosts);
	}

	/**
	 * Sets the list of IP address this network interface binds to.
	 * 
	 * @param bindTo
	 *            A comma-separated list of IP address to bind to
	 */
	public void setBindTo(String bindTo) throws IOException {
		StringTokenizer bindToTokens = new StringTokenizer(bindTo, ",");
		List bindToTokenList = new ArrayList();
		while (bindToTokens.hasMoreTokens()) {
			bindToTokenList.add(bindToTokens.nextToken().trim());
		}
		/* stop the old acceptors. */
		for (int acceptorIndex = 0, acceptorCount = acceptors.size(); acceptorIndex < acceptorCount; acceptorIndex++) {
			Acceptor acceptor = (Acceptor) acceptors.get(acceptorIndex);
			try {
				acceptor.close();
			} catch (IOException e) {
				/* swallow exception. */
			}
		}
		while (runningAcceptors > 0) {
			synchronized (syncObject) {
				try {
					syncObject.wait();
				} catch (InterruptedException e) {
				}
			}
		}
		acceptors.clear();
		for (int serverSocketIndex = 0; serverSocketIndex < bindToTokenList.size(); serverSocketIndex++) {
			ServerSocket serverSocket = new ServerSocket();
			serverSocket.bind(new InetSocketAddress((String) bindToTokenList.get(serverSocketIndex), port));
			Acceptor acceptor = new Acceptor(serverSocket);
			acceptors.add(acceptor);
		}
		setSoTimeout(timeout);
		List tempThreads = new ArrayList();
		synchronized (syncObject) {
			Iterator acceptors = this.acceptors.iterator();
			while (acceptors.hasNext()) {
				Thread t = new Thread((Acceptor) acceptors.next(), "Network Interface Acceptor");
				t.setDaemon(true);
				tempThreads.add(t);
			}
		}
		for (Iterator i = tempThreads.iterator(); i.hasNext();) {
			((Thread) i.next()).start();
			runningAcceptors++;
		}
	}

	/**
	 * Sets the list of allowed hosts to <code>allowedHosts</code>. The new
	 * list is in effect immediately after this method has finished.
	 * 
	 * @param allowedHosts
	 *            The new list of allowed hosts s
	 */
	public void setAllowedHosts(String allowedHosts) {
		StringTokenizer allowedHostsTokens = new StringTokenizer(allowedHosts, ",");
		List newAllowedHosts = new ArrayList();
		Map newAddressMatchers = new HashMap();
		while (allowedHostsTokens.hasMoreTokens()) {
			String allowedHost = allowedHostsTokens.nextToken().trim();
			String hostname = allowedHost;
			if (allowedHost.indexOf('/') != -1) {
				hostname = allowedHost.substring(0, allowedHost.indexOf('/'));
			}
			AddressType addressType = AddressIdentifier.getAddressType(hostname);
			if (addressType == AddressType.IPv4) {
				newAddressMatchers.put(allowedHost, new Inet4AddressMatcher(allowedHost));
				newAllowedHosts.add(allowedHost);
			} else if (addressType == AddressType.IPv6) {
				newAddressMatchers.put(allowedHost, new Inet6AddressMatcher(allowedHost));
				newAllowedHosts.add(allowedHost);
			} else if (allowedHost.equals("*")) {
				newAllowedHosts.add(allowedHost);
			} else {
				Logger.normal(NetworkInterface.class, "Ignoring invalid allowedHost: " + allowedHost);
			}
		}
		synchronized (syncObject) {
			this.allowedHosts.clear();
			this.allowedHosts.addAll(newAllowedHosts);
			this.addressMatchers.clear();
			this.addressMatchers.putAll(newAddressMatchers);
		}
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
		Iterator acceptors = this.acceptors.iterator();
		while (acceptors.hasNext()) {
			((Acceptor) acceptors.next()).setSoTimeout(timeout);
		}
	}

	/**
	 * Waits for a connection. If a timeout has been set using
	 * {@link #setSoTimeout(int)} and no connection is established this method
	 * will return after the specified timeout has been expired, throwing a
	 * {@link SocketTimeoutException}. If no timeout has been set this method
	 * will wait until a connection has been established.
	 * 
	 * @return The socket that is connected to the client
	 * @throws SocketTimeoutException
	 *             if the timeout has expired waiting for a connection
	 */
	public Socket accept() throws SocketTimeoutException {
		synchronized (syncObject) {
			while (acceptedSockets.size() == 0) {
				try {
					syncObject.wait(timeout);
				} catch (InterruptedException ie1) {
				}
				if ((timeout > 0) && (acceptedSockets.size() == 0)) {
					throw new SocketTimeoutException();
				}
			}
			return (Socket) acceptedSockets.remove(0);
		}
	}

	/**
	 * Closes this interface and all underlying server sockets.
	 * 
	 * @throws IOException
	 *             if an I/O exception occurs
	 * @see ServerSocket#close()
	 */
	public void close() throws IOException {
		IOException exception = null;
		Iterator acceptors = this.acceptors.iterator();
		while (acceptors.hasNext()) {
			Acceptor acceptor = (Acceptor) acceptors.next();
			try {
				acceptor.close();
			} catch (IOException ioe1) {
				exception = ioe1;
			}
		}
		if (exception != null) {
			throw (exception);
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
		public void run() {
			while (!closed) {
				try {
					Socket clientSocket = serverSocket.accept();
					InetAddress clientAddress = clientSocket.getInetAddress();
					Logger.minor(Acceptor.class, "Connection from " + clientAddress);

					/* check if the ip address is allowed */
					boolean addressMatched = false;
					synchronized (syncObject) {
						Iterator hosts = allowedHosts.iterator();
						while (!addressMatched && hosts.hasNext()) {
							String host = (String) hosts.next();
							AddressMatcher matcher = (AddressMatcher) addressMatchers.get(host);
							if (matcher != null) {
								addressMatched = matcher.matches(clientAddress);
							} else {
								addressMatched = "*".equals(host);
							}
						}
					}

					if (addressMatched) {
						synchronized (syncObject) {
							acceptedSockets.add(clientSocket);
							syncObject.notify();
						}
					} else {
						try {
							clientSocket.close();
						} catch (IOException ioe1) {
						}
						Logger.normal(Acceptor.class, "Denied connection to " + clientAddress);
					}
				} catch (SocketTimeoutException ste1) {
					Logger.minor(this, "Timeout");
				} catch (IOException ioe1) {
					Logger.minor(this, "Caught " + ioe1);
				}
			}
			NetworkInterface.this.acceptorStopped();
		}

	}

}
