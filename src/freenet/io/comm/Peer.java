/*
 * Dijjer - A Peer to Peer HTTP Cache
 * Copyright (C) 2004,2005 Change.Tv, Inc
 *
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
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package freenet.io.comm;

import java.io.*;
import java.net.*;

import freenet.io.AddressIdentifier;
import freenet.io.WritableToDataOutputStream;
import freenet.support.Logger;

/**
 * @author ian
 *
 * To change the template for this generated type comment go to Window - Preferences - Java - Code Generation - Code and
 * Comments
 */
public class Peer implements WritableToDataOutputStream {

    public static final String VERSION = "$Id: Peer.java,v 1.4 2005/08/25 17:28:19 amphibian Exp $";

    private final FreenetInetAddress addr;
	private final int _port;

	// Create a null peer
	public Peer() throws Exception {
		this(InetAddress.getLocalHost(), 0);
	}

	public Peer(DataInputStream dis) throws IOException {
		addr = new FreenetInetAddress(dis);
		_port = dis.readInt();
	}

	/**
	 * Create a Peer from an InetAddress and a port. The IP address is primary; that is
	 * to say, it will remain the same regardless of DNS changes. Don't do this if you
	 * are dealing with domain names whose IP may change.
	 */
	public Peer(InetAddress address, int port) {
		addr = new FreenetInetAddress(address);
		_port = port;
	}

	/**
	 * Create a Peer from a string. This may be an IP address or a domain name. If it
	 * is the latter, the name is primary rather than the IP address; 
	 * getHandshakeAddress() will do a new lookup on the name, and change the IP address
	 * if the domain name has changed.
	 * @param physical The string to be parsed, in the format [ ip or domain name ]:[ port number].
	 * @param allowUnknown If true, allow construction of the Peer even if the domain name
	 * lookup fails.
	 * @throws PeerParseException If the string is not valid e.g. if it doesn't contain a 
	 * port.
	 * @throws UnknownHostException If allowUnknown is not set, and a domain name which does
	 * not exist was passed in.
	 */
    public Peer(String physical, boolean allowUnknown) throws PeerParseException, UnknownHostException {
        int offset = physical.lastIndexOf(':'); // ipv6
        if(offset < 0) throw new PeerParseException();
        String host = physical.substring(0, offset);
        addr = new FreenetInetAddress(host, allowUnknown);
        String strport = physical.substring(offset+1);
        try {
            _port = Integer.parseInt(strport);
        } catch (NumberFormatException e) {
            throw new PeerParseException(e);
        }
    }
    
    public Peer(FreenetInetAddress addr, int port) {
    	this.addr = addr;
    	this._port = port;
	}

	public boolean isNull() {
		return _port == 0;
	}

	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Peer)) {
			return false;
		}

		final Peer peer = (Peer) o;

		if (_port != peer._port) {
			return false;
		}
		if(!addr.equals(peer.addr))
			return false;
		return true;
	}

	public boolean strictEquals(Object o) {
		if (this == o) {
			return true;
		}
		if(o == null) return false;
		if (!(o instanceof Peer)) {
			return false;
		}

		final Peer peer = (Peer) o;

		if (_port != peer._port) {
			return false;
		}
		if(!addr.strictEquals(peer.addr))
			return false;
		return true;
	}

	/**
	 * Get the IP address. Look it up if necessary, but return the last value if it
	 * has ever been looked up before; will not trigger a new lookup if it has been
	 * looked up before.
	 */
	public InetAddress getAddress() {
		return addr.getAddress();
	}
	
	/**
	 * Get the IP address, looking up the hostname if the hostname is primary, even if
	 * it has been looked up before. Typically called on a reconnect attempt, when the
	 * dyndns address may have changed.
	 */
	public InetAddress getHandshakeAddress() {
		return addr.getHandshakeAddress();
	}
	
	public int hashCode() {
		return addr.hashCode() + _port;
	}

	public int getPort() {
		return _port;
	}

	public String toString() {
		return addr.toString() + ":" + _port;
	}

	public void writeToDataOutputStream(DataOutputStream dos) throws IOException {
		addr.writeToDataOutputStream(dos);
		dos.writeInt(_port);
	}

	public FreenetInetAddress getFreenetAddress() {
		return addr;
	}
}