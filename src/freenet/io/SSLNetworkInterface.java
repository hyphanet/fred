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
import java.net.ServerSocket;

import javax.net.ssl.SSLServerSocket;

import freenet.crypt.SSL;
import freenet.support.Executor;
import freenet.support.Logger;

/**
 * An SSL extension to the {@link NetworkInterface} 
 * @author ET
 */
public class SSLNetworkInterface extends NetworkInterface {
	
	public static NetworkInterface create(int port, String bindTo, String allowedHosts, Executor executor, boolean ignoreUnbindableIP6) throws IOException {
		NetworkInterface iface = new SSLNetworkInterface(port, allowedHosts, executor);
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
	 * See {@link NetworkInterface}
	 */
	protected SSLNetworkInterface(int port, String allowedHosts, Executor executor) throws IOException {
		super(port, allowedHosts, executor);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected ServerSocket createServerSocket() throws IOException {
		SSLServerSocket serverSocket = (SSLServerSocket) SSL.createServerSocket();
		serverSocket.setNeedClientAuth(false);
		serverSocket.setUseClientMode(false);
		serverSocket.setWantClientAuth(false);

		serverSocket.setEnabledCipherSuites(new String[] {
		    "TLS_DHE_RSA_WITH_AES_256_CBC_SHA", // We want PFS (DHE)
		    // "TLS_RSA_WITH_AES_256_CBC_SHA",
		});

		return serverSocket;
	}
}
