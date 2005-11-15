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
package freenet.io.xfer;

import java.util.Iterator;
import java.util.LinkedList;

import freenet.support.Buffer;

/**
 * @author ian
 * 
 * To change the template for this generated type comment go to Window - Preferences - Java - Code Generation - Code and
 * Comments
 */
public class PartiallyReceivedBlock {

	byte[] _data;
	boolean[] _received;
	int _receivedCount = 0;
	int _packets, _packetSize;
	boolean _aborted = false;
	int _abortReason;
	String _abortDescription;
	LinkedList _packetReceivedListeners = new LinkedList();

	public PartiallyReceivedBlock(int packets, int packetSize, byte[] data) {
		if (data.length != packets * packetSize) {
			throw new RuntimeException("Length of data ("+data.length+") doesn't match packet number and size");
		}
		_data = data;
		_received = new boolean[packets];
		for (int x=0; x<_received.length; x++) {
			_received[x] = true;
		}
		_receivedCount = packets;
		_packets = packets;
		_packetSize = packetSize;
	}
	
	public PartiallyReceivedBlock(int packets, int packetSize) {
		_data = new byte[packets * packetSize];
		_received = new boolean[packets];
		_packets = packets;
		_packetSize = packetSize;
	}

	public synchronized LinkedList addListener(PacketReceivedListener listener) throws AbortedException {
		if (_aborted) {
			throw new AbortedException("Adding listener to aborted PRB");
		}
		_packetReceivedListeners.add(listener);
		LinkedList ret = new LinkedList();
		for (int x = 0; x < _packets; x++) {
			if (_received[x]) {
				ret.addLast(new Integer(x));
			}
		}
		return ret;
	}

	public synchronized boolean isReceived(int packetNo) throws AbortedException {
		if (_aborted) {
			throw new AbortedException("PRB is aborted");
		}
		return _received[packetNo];
	}
	
	public synchronized int getNumPackets() throws AbortedException {
		if (_aborted) {
			throw new AbortedException("PRB is aborted");
		}
		return _packets;
	}
	
	public synchronized int getPacketSize() throws AbortedException {
		if (_aborted) {
			throw new AbortedException("PRB is aborted");
		}
		return _packetSize;
	}
	
	public synchronized void addPacket(int position, Buffer packet) throws AbortedException {
		if (_aborted) {
			throw new AbortedException("PRB is aborted");
		}
		if (packet.getLength() != _packetSize) {
			throw new RuntimeException("New packet size "+packet.getLength()+" but expecting packet of size "+_packetSize);
		}
		if (!_received[position]) {
			_receivedCount++;
		}
		packet.copyTo(_data, position * _packetSize);
		_received[position] = true;
		for (Iterator i = _packetReceivedListeners.iterator(); i.hasNext();) {
			PacketReceivedListener prl = (PacketReceivedListener) i.next();
			prl.packetReceived(position);
		}
	}

	public boolean allReceived() throws AbortedException {
		if (_aborted) {
			throw new AbortedException("PRB is aborted");
		}
		return _receivedCount == _packets;
	}
	
	public byte[] getBlock() throws AbortedException {
		if (_aborted) {
			throw new AbortedException("PRB is aborted");
		}
		if (!allReceived()) {
			throw new RuntimeException("Tried to get block before all packets received");
		}
		return _data;
	}
	
	public Buffer getPacket(int x) throws AbortedException {
		if (_aborted) {
			throw new AbortedException("PRB is aborted");
		}
		return new Buffer(_data, x * _packetSize, _packetSize);
	}

	public synchronized void removeListener(PacketReceivedListener listener) {
		_packetReceivedListeners.remove(listener);
	}

	public synchronized void abort(int reason, String description) {
		_aborted = true;
		_abortReason = reason;
		_abortDescription = description;
		for (Iterator i = _packetReceivedListeners.iterator(); i.hasNext();) {
			PacketReceivedListener prl = (PacketReceivedListener) i.next();
			prl.receiveAborted(reason, description);
		}
	}
	
	public synchronized boolean isAborted() {
		return _aborted;
	}
	
	public synchronized int getAbortReason() {
		return _abortReason;
	}
	
	public synchronized String getAbortDescription() {
		return _abortDescription;
	}
	
	public static interface PacketReceivedListener {

		public void packetReceived(int packetNo);
		
		public void receiveAborted(int reason, String description);
	}
}
