/*
  LowLevelFilter.java / Freenet
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
 * Filter interface used by Freenet to decrypt incoming packets.
 */
public interface LowLevelFilter {

    /**
     * Process an incoming packet. This method should call
     * USM.decodePacket() and USM.checkFilters() if necessary to 
     * decode and dispatch messages.
     * @param buf The buffer to read from.
     * @param offset The offset to start reading from.
     * @param length The length in bytes to read.
     * @param peer The peer which sent us the packet. We only know
     * the Peer because it's incoming; we are supposed to create
     * or find PeerContext's for the Message's.
     */
    void process(byte[] buf, int offset, int length, Peer peer);

    // Outgoing packets are handled elsewhere...
    
    /**
     * Is the given connection closed?
     */
    boolean isDisconnected(PeerContext context);
}
