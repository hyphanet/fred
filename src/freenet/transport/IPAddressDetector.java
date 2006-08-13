/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.transport;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.Vector;

import freenet.node.NodeIPDetector;
import freenet.support.Logger;

/**
 * A class to autodetect our IP address(es)
 */

public class IPAddressDetector implements Runnable {
	
	//private String preferedAddressString = null;
	private final int interval;
	private final NodeIPDetector detector;
	public IPAddressDetector(int interval, NodeIPDetector detector) {
		this.interval = interval;
		this.detector = detector;
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
	public InetAddress[] getAddress() {
		return getAddress(0);
	}

	/**
	 * Get the IP address
	 * @return Detected ip address
	 */
	public InetAddress[] getAddress(long recheckTime) {
		if(System.currentTimeMillis() > (lastDetectedTime + recheckTime)
				|| lastAddressList == null)
			checkpoint();
		return lastAddressList == null ? new InetAddress[0] : lastAddressList;
	}

	boolean old = false;

	/**
	 * Execute a checkpoint - detect our internet IP address and log it
	 * @param preferedAddress An address that for some reason is prefered above others. Might be null
	 */
	protected synchronized void checkpoint() {
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

		InetAddress oldAddress = lastInetAddress;
		onGetAddresses(addrs);
		lastDetectedTime = System.currentTimeMillis();
		if ((oldAddress != null) && (lastInetAddress != null) && 
		        !lastInetAddress.equals(oldAddress)) {
			Logger.minor(
				this,
				"Public IP Address changed from "
					+ oldAddress.getHostAddress()
					+ " to "
					+ lastInetAddress.getHostAddress());
			detector.redetectAddress();
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
	protected void onGetAddresses(Vector v) {
		Vector output = new Vector();
		boolean logDEBUG = Logger.shouldLog(Logger.DEBUG, this);
		if (logDEBUG)
			Logger.debug(
				this,
				"onGetAddresses found " + v.size() + " potential addresses)");
		if (v.size() == 0) {
			Logger.error(this, "No addresses found!");
			lastAddressList = null;
		} else {
//			InetAddress lastNonValidAddress = null;
			for (int x = 0; x < v.size(); x++) {
				if (v.elementAt(x) != null) {
					InetAddress i = (InetAddress) (v.elementAt(x));
					if (logDEBUG)
						Logger.debug(
							this,
							"Address " + x + ": " + i);
					if(i.isAnyLocalAddress()) {
						// Wildcard address, 0.0.0.0, ignore.
					} else if(i.isLinkLocalAddress() || i.isLoopbackAddress() ||
							i.isSiteLocalAddress()) {
						if(detector.includeLocalAddressesInNoderefs()) {
							output.add(i);
						}
					} else if(i.isMulticastAddress()) {
						// Ignore
					} else {
						output.add(i);
					}
				}
			}
		}
		lastAddressList = (InetAddress[]) output.toArray(new InetAddress[output.size()]);
	}

	protected boolean isInternetAddress(InetAddress addr) {
		return detector.includeLocalAddressesInNoderefs() || IPUtil.checkAddress(addr);
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

	public void clearCached() {
		lastAddressList = null;
		lastDetectedTime = -1;
	}
}
