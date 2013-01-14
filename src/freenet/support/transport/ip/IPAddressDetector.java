/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.support.transport.ip;

import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;

import freenet.io.AddressIdentifier;
import freenet.node.NodeIPDetector;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.io.InetAddressComparator;

/**
 * A class to autodetect our IP address(es)
 */

public class IPAddressDetector implements Runnable {
	
	private static volatile boolean logDEBUG;

	static {
		Logger.registerClass(IPAddressDetector.class);
	}
	
	//private String preferedAddressString = null;
	private final int interval;
	private final NodeIPDetector detector;
        /**
         * 
         * @param interval
         * @param detector
         */
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

	InetAddress[] lastAddressList = null;
	long lastDetectedTime = -1;
	
	/** Fetch the currently detected IP address. If not detected yet, run the
	 * detection. DO NOT callback to detector.redetectAddresses().
	 * @return
	 */
	public InetAddress[] getAddressNoCallback() {
		if(System.currentTimeMillis() > (lastDetectedTime + interval)) {
			checkpoint();
		}
		return lastAddressList == null ? new InetAddress[0] : lastAddressList;
	}

	/** 
	 * Fetches the currently detected IP address. If not detected yet a detection is forced.
	 * If the IP address list changes, call the callback on the detector, off-thread, using the
	 * given Executor. This method is intended to be called by code other than the detector 
	 * itself.
	 * @return Detected ip addresses
	 */
	public InetAddress[] getAddress(Executor executor) {
		assert(executor != null);
		if(System.currentTimeMillis() > (lastDetectedTime + interval)) {
			if(checkpoint()) {
				executor.execute(new Runnable() {

					@Override
					public void run() {
						detector.redetectAddress();
					}
					
				});
			}
		}
		return lastAddressList == null ? new InetAddress[0] : lastAddressList;
	}
	
	boolean old = false;

	/**
	 * Execute a checkpoint - detect our internet IP address and log it
	 */
	protected synchronized boolean checkpoint() {
		final boolean logDEBUG = IPAddressDetector.logDEBUG;
		List<InetAddress> addrs = new ArrayList<InetAddress>();

		Enumeration<java.net.NetworkInterface> interfaces = null;
		try {
			interfaces = java.net.NetworkInterface.getNetworkInterfaces();
		} catch (SocketException e) {
			Logger.error(
				this,
				"SocketException trying to detect NetworkInterfaces: "+e,
				e);
			addrs.add(oldDetect());
			old = true;
		}

		if (!old) {
			while (interfaces.hasMoreElements()) {
				java.net.NetworkInterface iface = interfaces.nextElement();
				if (logDEBUG)
					Logger.debug(
						this,
						"Scanning NetworkInterface " + iface.getDisplayName());
				Enumeration<InetAddress> ee = iface.getInetAddresses();
				while (ee.hasMoreElements()) {

					InetAddress addr = ee.nextElement();
					if ((addr instanceof Inet6Address) && !(addr.isLinkLocalAddress() || IPUtil.isSiteLocalAddress(addr))) {
						try {
							// strip scope_id from global addresses
							addr = InetAddress.getByAddress(addr.getAddress());
						} catch(UnknownHostException e) {
							// ignore/impossible
						}
					}
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

		InetAddress[] oldAddressList = lastAddressList;
		onGetAddresses(addrs);
		lastDetectedTime = System.currentTimeMillis();
		return addressListChanged(oldAddressList, lastAddressList);
	}

	private boolean addressListChanged(InetAddress[] oldList,
			InetAddress[] newList) {
		if(oldList == null) return newList != null;
		if(oldList == newList) return false;
		if(oldList.length != newList.length) return true;
		InetAddress[] a = Arrays.copyOf(oldList, oldList.length);
		InetAddress[] b = Arrays.copyOf(newList, newList.length);
		Arrays.sort(a, InetAddressComparator.COMPARATOR);
		Arrays.sort(b, InetAddressComparator.COMPARATOR);
		return !Arrays.deepEquals(a, b);
	}

		/**
         *
         * @return
         */
        protected InetAddress oldDetect() {
		boolean shouldLog = Logger.shouldLog(LogLevel.DEBUG, this);
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
			// The ip is a.root-servers.net, 53 is DNS
			try {
				ds.connect(InetAddress.getByName("198.41.0.4"), 53);
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

	/**
	 * Do something with the list of detected IP addresses.
	 * 
	 * @param addrs
	 *            Vector of InetAddresses
	 */
	protected void onGetAddresses(List<InetAddress> addrs) {
		final boolean logDEBUG = IPAddressDetector.logDEBUG;
		List<InetAddress> output = new ArrayList<InetAddress>();
		if (logDEBUG)
			Logger.debug(
				this,
				"onGetAddresses found " + addrs.size() + " potential addresses)");
		if (addrs.size() == 0) {
			Logger.error(this, "No addresses found!");
			lastAddressList = null;
			return;
		} else {
//			InetAddress lastNonValidAddress = null;
			for (int x = 0; x < addrs.size(); x++) {
				if (addrs.get(x) != null) {
					InetAddress i = addrs.get(x);
					if (logDEBUG)
						Logger.debug(
							this,
							"Address " + x + ": " + i);
					if(i.isAnyLocalAddress()) {
						// Wildcard address, 0.0.0.0, ignore.
					} else if(i.isLinkLocalAddress() || i.isLoopbackAddress() ||
							i.isSiteLocalAddress()) {
						// Will be filtered out later if necessary.
						output.add(i);
					} else if(i.isMulticastAddress()) {
						// Ignore
					} else {
						// Ignore ISATAP addresses
						// @see http://archives.freenetproject.org/message/20071129.220955.ac2a2a36.en.html
						if(!AddressIdentifier.isAnISATAPIPv6Address(i.toString()))
							output.add(i);
					}
				}
			}
		}
		lastAddressList = output.toArray(new InetAddress[output.size()]);
	}

	@Override
	public void run() {
		freenet.support.Logger.OSThread.logPID(this);
		while(true) {
			try {
				Thread.sleep(interval);
			} catch (InterruptedException e) {
				// Ignore
			}
			try {
				if(checkpoint()) {
					detector.redetectAddress();
				}
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t, t);
			}
		}
	}

        /**
         *
         */
        public void clearCached() {
		lastAddressList = null;
		lastDetectedTime = -1;
	}
}
