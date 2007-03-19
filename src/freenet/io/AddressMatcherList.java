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
public class AddressMatcherList {
	
	/** List of allowed hosts. */
	protected final List/* <String> */allowedHosts = new ArrayList();

	/** Maps allowed hosts to address matchers, if possible. */
	protected final Map/* <String, AddressMatcher> */addressMatchers = new HashMap();

	public AddressMatcherList(String allowedHosts) {
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
