/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.transport;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.Vector;

import freenet.node.Node;
import freenet.support.Logger;

/**
 * A class to autodetect our IP address(es)
 */

public class IPAddressDetector implements Runnable {
	
	//private String preferedAddressString = null;
	private final int interval;
	private final Node node;
	public IPAddressDetector(int interval, Node node) {
		this.interval = interval;
		this.node = node;
	}

	/**
	 * @return our name
	 */
	public String getCheckpointName() {
		return "Autodetection of IP addresses";
	}

	/** 
	 * @return next scheduling point
	 */
	public long nextCheckpoint() {
		return System.currentTimeMillis() + interval; // We are pretty cheap
	}

	InetAddress lastInetAddress = null;
	InetAddress[] lastAddressList = null;
	long lastDetectedTime = -1;

	/** 
	 * Fetches the currently detected IP address. If not detected yet a detection is forced
	 * @return Detected ip address
	 */
	public InetAddress getAddress() {
		return getAddress(0, null);
	}

	/**
	 * Get the IP address
	 * @param preferedAddress An address that for some reason is prefered above others. Might be null
	 * @return Detected ip address
	 */
	public InetAddress getAddress(long recheckTime, String preferedAddress) {
		if ((lastInetAddress == null)
			|| (System.currentTimeMillis() > (lastDetectedTime + recheckTime)))
			checkpoint(preferedAddress);
		return lastInetAddress;
	}

	/**
	 * Get the IP address
	 * @return Detected ip address
	 */
	public InetAddress getAddress(long recheckTime) {
		return getAddress(recheckTime, null);
	}

	public void checkpoint() {
		checkpoint((InetAddress)null);
	}

	boolean old = false;

	protected synchronized void checkpoint(String preferredAddress) {
	    InetAddress preferredInetAddress = null;
		try {
			preferredInetAddress = InetAddress.getByName(preferredAddress);
			//It there was something preferred then convert it to a proper class
		} catch (UnknownHostException e) {
		}
		checkpoint(preferredInetAddress);
	}
	
	/**
	 * Execute a checkpoint - detect our internet IP address and log it
	 * @param preferedAddress An address that for some reason is prefered above others. Might be null
	 */
	protected synchronized void checkpoint(InetAddress preferedInetAddress) {
		boolean logDEBUG = Logger.shouldLog(Logger.DEBUG, this);
		Vector addrs = new Vector();

		Enumeration interfaces = null;
		try {
			interfaces = java.net.NetworkInterface.getNetworkInterfaces();
		} catch (NoClassDefFoundError e) {
			addrs.add(oldDetect());
			old = true;
		} catch (SocketException e) {
			Logger.error(
				this,
				"SocketException trying to detect NetworkInterfaces",
				e);
			addrs.add(oldDetect());
			old = true;
		}

		if (!old) {
			while (interfaces.hasMoreElements()) {
				java.net.NetworkInterface iface =
					(java.net.NetworkInterface) (interfaces.nextElement());
				if (logDEBUG)
					Logger.debug(
						this,
						"Scanning NetworkInterface " + iface.getDisplayName());
				Enumeration ee = iface.getInetAddresses();
				while (ee.hasMoreElements()) {

					InetAddress addr = (InetAddress) (ee.nextElement());
					addrs.add(addr);
					if (logDEBUG)
						Logger.debug(
							this,
							"Adding address "
								+ addr
								+ " from "
								+ iface.getDisplayName());
				}
				if (logDEBUG)
					Logger.debug(
						this,
						"Finished scanning interface " + iface.getDisplayName());
			}
			if (logDEBUG)
				Logger.debug(
					this,
					"Finished scanning interfaces");
		}

		if ((preferedInetAddress == null)
			&& (lastInetAddress != null)
				? isInternetAddress(lastInetAddress)
				: true) //If no specific other address is preferred then we prefer to keep our old address
			preferedInetAddress = lastInetAddress;

		InetAddress oldAddress = lastInetAddress;
		onGetAddresses(addrs, preferedInetAddress);
		lastDetectedTime = System.currentTimeMillis();
		if ((oldAddress != null) && (lastInetAddress != null) && 
		        !lastInetAddress.equals(oldAddress)) {
			Logger.minor(
				this,
				"Public IP Address changed from "
					+ oldAddress.getHostAddress()
					+ " to "
					+ lastInetAddress.getHostAddress());
			node.redetectAddress();
			// We know it changed
		}
	}

	protected InetAddress oldDetect() {
		boolean shouldLog = Logger.shouldLog(Logger.DEBUG, this);
		if (shouldLog)
			Logger.debug(
				this,
				"Running old style detection code");
		DatagramSocket ds = null;
		try {
			try {
				ds = new DatagramSocket();
			} catch (SocketException e) {
				Logger.error(this, "SocketException", e);
				return null;
			}

			// This does not transfer any data
			// The ip is a.root-servers.net, 42 is DNS
			try {
				ds.connect(InetAddress.getByName("198.41.0.4"), 42);
			} catch (UnknownHostException ex) {
				Logger.error(this, "UnknownHostException", ex);
				return null;
			}
			return ds.getLocalAddress();
		} finally {
			if (ds != null) {
				ds.close();
			}
		}
	}

	/** Do something with the list of detected IP addresses.
	 * @param v Vector of InetAddresses
	 * @param preferedInetAddress An address that for some reason is prefered above others. Might be null
	 */
	protected void onGetAddresses(Vector v, InetAddress preferedInetAddress) {
		boolean logDEBUG = Logger.shouldLog(Logger.DEBUG, this);
		if (logDEBUG)
			Logger.debug(
				this,
				"onGetAddresses found " + v.size() + " potential addresses)");
		boolean detectedInetAddress = false;
		InetAddress addrDetected = null;
		if (v.size() == 0) {
			Logger.error(this, "No addresses found!");
			addrDetected = null;
		} else {
//			InetAddress lastNonValidAddress = null;
			for (int x = 0; x < v.size(); x++) {
				if (v.elementAt(x) != null) {
					InetAddress i = (InetAddress) (v.elementAt(x));
					if (logDEBUG)
						Logger.debug(
							this,
							"Address " + x + ": " + i);
					if (isInternetAddress(i)) {
						//Do not even consider this address if it isn't globally addressable
						if (logDEBUG)
							Logger.debug(
								this,
								"Setting default address to "
									+ i.getHostAddress());

						addrDetected = i;
						//Use the last detected valid IP as 'detected' IP
						detectedInetAddress = true;
						if ((preferedInetAddress != null)
							&& addrDetected.equals(
								preferedInetAddress)) { //Prefer the specified address if it is still available to us. Do not look for more ones
							if (logDEBUG)
								Logger.debug(
									this,
									"Detected address is the preferred address, setting final address to "
										+ lastInetAddress.getHostAddress());
							lastInetAddress = addrDetected;
							return;
						}

					}// else
//						lastNonValidAddress = i;
				}
			}
			//If we are here we didn't manage to find a valid globally addressable IP. Do the best of the situation, return the last valid non-addressable IP
			//This address will be used by the node if the user has configured localIsOK.
//			if (lastInetAddress == null || (!detectedInetAddress))
//				lastInetAddress = lastNonValidAddress;
		}
		lastInetAddress = addrDetected;
		// FIXME: add support for multihoming
	}

	protected boolean isInternetAddress(InetAddress addr) {
		return node.includeLocalAddressesInNoderefs || IPUtil.checkAddress(addr);
	}

	public void run() {
		while(true) {
			try {
				Thread.sleep(interval);
			} catch (InterruptedException e) {
				// Ignore
			}
			try {
				checkpoint();
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t, t);
			}
		}
	}
}
