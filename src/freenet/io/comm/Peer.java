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

    public static final String VERSION = "$Id: Peer.java,v 1.1 2005/01/29 19:12:10 amphibian Exp $";

	private final InetAddress _address;
	private final int _port;

	// Create a null peer
	public Peer() throws Exception {
		this(InetAddress.getLocalHost(), 0);
	}

	public Peer(DataInputStream dis) throws IOException {
		byte[] ba = new byte[4];
		dis.read(ba);
		_address = InetAddress.getByAddress(ba);
		_port = dis.readInt();
	}

	public Peer(InetAddress address, int port) {
		_address = address;
		_port = port;
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
		if (!_address.equals(peer._address)) {
			return false;
		}
		return true;
	}

	public InetAddress getAddress() {
		return _address;
	}

	public int hashCode() {
		return _address.hashCode() + _port;
	}

	public int getPort() {
		return _port;
	}

	public String toString() {
		return (_address != null ? _address.getHostName() : "null") + ":" + _port;
	}

	public void writeToDataOutputStream(DataOutputStream dos) throws IOException {
		dos.write(_address.getAddress());
		dos.writeInt(_port);
	}
}