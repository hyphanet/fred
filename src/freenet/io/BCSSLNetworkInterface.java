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
/**
* Exactly same as SSLNetworkInterface
* Only renamed SSL by BCModifiedSSL through out the class
* This can be got rid off when BCSSL becomes the actual SSL
* i.e. when the changes in BCSSL are carried to SSL, this class would be worthless
*/
import freenet.crypt.BCModifiedSSL;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Arrays;

import freenet.support.Executor;
import javax.net.ssl.SSLServerSocket;

/**
 * An SSL extension to the {@link NetworkInterface} 
 * @author ET
 */
public class BCSSLNetworkInterface extends NetworkInterface {
	
	public static NetworkInterface create(int port, String bindTo, String allowedHosts, Executor executor, boolean ignoreUnbindableIP6) throws IOException {
		NetworkInterface iface = new BCSSLNetworkInterface(port, allowedHosts, executor);
		String[] failedBind = iface.setBindTo(bindTo, ignoreUnbindableIP6);
		if(failedBind != null) {
			System.err.println("Could not bind to some of the interfaces specified for port "+port+" : "+Arrays.toString(failedBind));
		}
		return iface;
	}

	/**
	 * See {@link NetworkInterface}
	 */
	protected BCSSLNetworkInterface(int port, String allowedHosts, Executor executor) throws IOException {
		super(port, allowedHosts, executor);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected ServerSocket createServerSocket() throws IOException {
		SSLServerSocket serverSocket = (SSLServerSocket) BCModifiedSSL.createServerSocket();
		serverSocket.setNeedClientAuth(false);
		serverSocket.setUseClientMode(false);
		serverSocket.setWantClientAuth(false);
		return serverSocket;
	}
}
