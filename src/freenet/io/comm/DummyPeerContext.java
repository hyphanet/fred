/*
  DummyPeerContext.java / Freenet
  Copyright (C) amphibian
  Copyright (C) 2005-2006 The Free Network project

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

package freenet.io.comm;

/**
 * @author amphibian
 * 
 * Default PeerContext if we don't have a LowLevelFilter installed.
 * Just carries the Peer.
 */
public class DummyPeerContext implements PeerContext {

    private final Peer peer;
    
    public Peer getPeer() {
        return peer;
    }
    
    DummyPeerContext(Peer p) {
        peer = p;
    }

	public void forceDisconnect() {
		// Do nothing
	}

	public boolean isRoutable() {
		return false;
	}
	
	public boolean isConnected() {
		return false;
	}

	public void reportOutgoingBytes(int length) {
		// Ignore
	}
}
