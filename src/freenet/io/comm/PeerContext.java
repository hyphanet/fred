/*
  PeerContext.java / Freenet
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
 * Everything that is needed to send a message, including the Peer.
 * Implemented by PeerNode, for example.
 */
public interface PeerContext {
    // Largely opaque interface for now
    Peer getPeer();

    /** Force the peer to disconnect */
	void forceDisconnect();

	/** Is the peer connected? Have we established the session link? */
	boolean isConnected();
	
	/** Is the peer connected? are we able to route requests to it? */
	boolean isRoutable();
}
