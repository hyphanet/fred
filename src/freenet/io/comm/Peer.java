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

import freenet.io.WritableToDataOutputStream;

/**
 * @author ian
 *
 * To change the template for this generated type comment go to Window - Preferences - Java - Code Generation - Code and
 * Comments
 */
public class Peer implements WritableToDataOutputStream {

    public static final String VERSION = "$Id: Peer.java,v 1.4 2005/08/25 17:28:19 amphibian Exp $";
	
	// hostname - only set if we were created with a hostname
	// and not an address
	private final String hostname;
	private final InetAddress _address;
	private final int _port;

	// Create a null peer
	public Peer() throws Exception {
		this(InetAddress.getLocalHost(), 0);
	}

	public Peer(DataInputStream dis) throws IOException {
		byte[] ba = new byte[4];
		dis.readFully(ba);
		_address = InetAddress.getByAddress(ba);
		_port = dis.readInt();
		this.hostname = null;
	}

	public Peer(InetAddress address, int port) {
		_address = address;
		_port = port;
		this.hostname = null;
	}

	/**
     * @param physical
     */
    public Peer(String physical) throws PeerParseException {
        int offset = physical.lastIndexOf(':'); // ipv6
        if(offset < 0) throw new PeerParseException();
	this.hostname = physical.substring(0, offset);
        String strport = physical.substring(offset+1);
	// we're created with a hostname so delay the lookup of the address
	// until it's needed to work better with dynamic DNS hostnames
	this._address = null;
        try {
            _port = Integer.parseInt(strport);
        } catch (NumberFormatException e) {
            throw new PeerParseException(e);
        }
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
		if (this._address != null) {
			if (!_address.equals(peer._address)) {
				return false;
			}
		} else {
			if (!hostname.equals(peer.hostname)) {
				return false;
			}
		}
		return true;
	}

	public InetAddress getAddress() {
		if (_address != null) {
			return _address;
		} else {
			/* 
			 * Peers are constructed from an address once
			 * a handshake has been completed, so this
			 * lookup will only be performed during a 
			 * handshake - it doesn't mean we perform
			 * a DNS lookup with every packet we send.
			 */
			try {
				return InetAddress.getByName(hostname);
			} catch (UnknownHostException e) {
				return null;
			}
		}
	}

	public int hashCode() {
		if (_address != null) {
			return _address.hashCode() + _port;
		} else {
			return hostname.hashCode() + _port;
		}
	}

	public int getPort() {
		return _port;
	}

	public String toString() {
		if (_address != null) {
			return getHostName(_address) + ":" + _port;
		} else {
			return hostname + ":" + _port;
		}
	}

	public void writeToDataOutputStream(DataOutputStream dos) throws IOException {
		InetAddress addr = this.getAddress();
		if (addr == null) throw new UnknownHostException();
		dos.write(addr.getAddress());
		dos.writeInt(_port);
	}

	/**
	 * Return the hostname or the IP address of the given InetAddress.
	 * Does not attempt to do a reverse lookup; if the hostname is
	 * known, return it, otherwise return the textual IP address.
	 */
	public static String getHostName(InetAddress primaryIPAddress) {
		String s = primaryIPAddress.toString();
		String addr = s.substring(0, s.indexOf('/')).trim();
		if(addr.length() == 0)
			return primaryIPAddress.getHostAddress();
		else
			return addr;
	}
}