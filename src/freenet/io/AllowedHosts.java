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

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import freenet.io.AddressIdentifier.AddressType;
import freenet.support.Logger;

/** Implementation of allowedHosts */
public class AllowedHosts {
	
	/** List of allowed hosts. */
	protected final List/* <String> */allowedHosts = new ArrayList();

	/** Maps allowed hosts to address matchers, if possible. */
	protected final Map/* <String, AddressMatcher> */addressMatchers = new HashMap();

	public AllowedHosts(String allowedHosts) {
		setAllowedHosts(allowedHosts);
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
		synchronized (this) {
			this.allowedHosts.clear();
			this.allowedHosts.addAll(newAllowedHosts);
			this.addressMatchers.clear();
			this.addressMatchers.putAll(newAddressMatchers);
		}
	}

	public synchronized boolean allowed(AddressType clientAddressType, InetAddress clientAddress) {
		boolean addressMatched = false;
		Iterator hosts = allowedHosts.iterator();
		while (!addressMatched && hosts.hasNext()) {
			String host = (String) hosts.next();
			AddressMatcher matcher = (AddressMatcher) addressMatchers.get(host);
			
			if (matcher != null && clientAddressType == matcher.getAddressType()) {
				addressMatched = matcher.matches(clientAddress);
			} else {
				addressMatched = "*".equals(host);
			}
		}
		return addressMatched;
	}

}
