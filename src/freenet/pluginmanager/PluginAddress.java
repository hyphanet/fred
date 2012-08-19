package freenet.pluginmanager;

import freenet.io.comm.FreenetInetAddress;

public interface PluginAddress{
	
	/** 
	 * An address that can be stored, and used later on for a noderef. 
	 * The plugin should be able to rebuild it from the String.
	 * @return
	 */
	public String toStringAddress();
	
	public String toString();
	
	public boolean laxEquals(Object o);
	
	/**
	 * Make sure if we have no information then we call it null.
	 * For e.g. if no IP address, port number or host name is present then it should equate it to null.
	 * Check class Peer for IP based implementation.
	 * @param o
	 * @return
	 */
	public boolean equals(Object o);
	
	public boolean strictEquals(Object o);
	
	/**
	 * Byte representation of the physical location. We need this for authentication. 
	 * So it should be constant for one object. Otherwise JFK may fail.
	 * For IP based addresses it should return the same InetAddress in bytes.
	 * @return
	 */
	public byte[] getBytes();
	
	/**
	 * Get only the physical location, excluding the transport specific/ user specific data.
	 * For e.g. in case of Inet based addresses, it ignores the port number.
	 * The implementation may not apply in all cases.
	 * @return
	 */
	public PluginAddress getPhysicalAddress();
	
	public void updateHostName() throws UnsupportedIPAddressOperationException;
	
	/**
	 * @return Return a new address with the host name dropped,
	 * if it is an IP based transport or else if applicable.
	 * @throws UnsupportedIPAddressOperationException
	 */
	public PluginAddress dropHostName() throws UnsupportedIPAddressOperationException;
	
	/**
	 * If the address uses Inet based IP addresses.
	 * @return
	 * @throws UnsupportedIPAddressOperationException
	 */
	public FreenetInetAddress getFreenetAddress() throws UnsupportedIPAddressOperationException;
	
	public int getPortNumber() throws UnsupportedIPAddressOperationException;
	
}