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
import java.net.Inet4Address;
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

import freenet.support.Logger;

/**
 * @author David Roden &lt;droden@gmail.com&gt;
 * @version $Id$
 */
public class NetworkInterface {

	protected final Object syncObject = new Object();
	private final List/* <Acceptor> */acceptors = new ArrayList();
	protected final List/* <String> */allowedHosts = new ArrayList();
	protected final Map/* <String, Inet4AddressMatcher> */addressMatchers = new HashMap();
	protected final List/* <Socket> */acceptedSockets = new ArrayList();
	private int timeout = 0;

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
		StringTokenizer bindToTokens = new StringTokenizer(bindTo, ",");
		List bindToTokenList = new ArrayList();
		while (bindToTokens.hasMoreTokens()) {
			bindToTokenList.add(bindToTokens.nextToken().trim());
		}
		for (int serverSocketIndex = 0; serverSocketIndex < bindToTokenList.size(); serverSocketIndex++) {
			ServerSocket serverSocket = new ServerSocket();
			serverSocket.bind(new InetSocketAddress((String) bindToTokenList.get(serverSocketIndex), port));
			Acceptor acceptor = new Acceptor(serverSocket);
			acceptors.add(acceptor);
			new Thread(acceptor).start();
		}
		setAllowedHosts(allowedHosts);
	}

	public void setAllowedHosts(String allowedHosts) {
		StringTokenizer allowedHostsTokens = new StringTokenizer(allowedHosts, ",");
		synchronized (syncObject) {
			this.allowedHosts.clear();
			this.addressMatchers.clear();
			while (allowedHostsTokens.hasMoreTokens()) {
				String allowedHost = allowedHostsTokens.nextToken().trim();
				this.allowedHosts.add(allowedHost);
				if (allowedHost.equals("*")) {
					addressMatchers.put("*", new Inet4AddressMatcher("0.0.0.0/0"));
				} else if (!Character.isLetter(allowedHost.charAt(0))) {
					addressMatchers.put(allowedHost, new Inet4AddressMatcher(allowedHost));
				}
			}
		}
	}

	public void setSoTimeout(int timeout) throws SocketException {
		Iterator acceptors = this.acceptors.iterator();
		while (acceptors.hasNext()) {
			((Acceptor) acceptors.next()).setSoTimeout(timeout);
		}
	}

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

	private class Acceptor implements Runnable {

		private final ServerSocket serverSocket;
		private boolean closed = false;

		public Acceptor(ServerSocket serverSocket) {
			this.serverSocket = serverSocket;
		}

		public void setSoTimeout(int timeout) throws SocketException {
			serverSocket.setSoTimeout(timeout);
		}

		public void close() throws IOException {
			closed = true;
			serverSocket.close();
		}

		public void run() {
			while (!closed) {
				try {
					Socket clientSocket = serverSocket.accept();
					InetAddress clientAddress = clientSocket.getInetAddress();
					String clientHostName = clientAddress.getHostName();

					/* check if the ip address is allowed */
					boolean addressMatched = false;
					synchronized (syncObject) {
						Iterator hosts = allowedHosts.iterator();
						while (!addressMatched && hosts.hasNext()) {
							String host = (String) hosts.next();
							Inet4AddressMatcher matcher = (Inet4AddressMatcher) addressMatchers.get(host);
							if (matcher != null) {
								addressMatched = matcher.matches((Inet4Address) clientAddress);
							} else {
								addressMatched = clientHostName.equalsIgnoreCase(host);
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
						Logger.normal(Acceptor.class, "Denied connection to " + clientHostName);
					}
				} catch (SocketTimeoutException ste1) {
				} catch (IOException ioe1) {
				}
			}
		}

	}

}
