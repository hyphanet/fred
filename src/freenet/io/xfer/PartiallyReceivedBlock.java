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

import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;

import freenet.support.Buffer;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/**
 * @author ian
 * 
 * To change the template for this generated type comment go to Window - Preferences - Java - Code Generation - Code and
 * Comments
 */
public class PartiallyReceivedBlock {

	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
				logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}
	
	byte[] _data;
	boolean[] _received;
	int _receivedCount;
	public final int _packets, _packetSize;
	boolean _aborted;
	boolean _abortedLocally;
	int _abortReason;
	String _abortDescription;
	ArrayList<PacketReceivedListener> _packetReceivedListeners = new ArrayList<PacketReceivedListener>();

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

	public synchronized Deque<Integer> addListener(PacketReceivedListener listener) throws AbortedException {
		if (_aborted) {
			throw new AbortedException("Adding listener to aborted PRB");
		}
		_packetReceivedListeners.add(listener);
		Deque<Integer> ret = new LinkedList<Integer>();
		for (int x = 0; x < _packets; x++) {
			if (_received[x]) {
				ret.addLast(x);
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
	
	public void addPacket(int position, Buffer packet) throws AbortedException {
		
		PacketReceivedListener[] prls;
		
		synchronized(this) {
			if (_aborted) {
				throw new AbortedException("PRB is aborted");
			}
			if (packet.getLength() != _packetSize) {
				throw new RuntimeException("New packet size "+packet.getLength()+" but expecting packet of size "+_packetSize);
			}
			if (_received[position])
				return;
			
			_receivedCount++;
			packet.copyTo(_data, position * _packetSize);
			_received[position] = true;
			
			// FIXME keep it as as an array
			prls = _packetReceivedListeners.toArray(new PacketReceivedListener[_packetReceivedListeners.size()]);
		}
		
		
		for (PacketReceivedListener prl: prls) {
			prl.packetReceived(position);
		}
	}
	
	public synchronized boolean allReceivedAndNotAborted() {
		return _receivedCount == _packets && !_aborted;
	}

	public synchronized boolean allReceived() throws AbortedException {
		if(_receivedCount == _packets) {
			if(logDEBUG) Logger.debug(this, "Received "+_receivedCount+" of "+_packets+" on "+this);
			return true;
		}
		if (_aborted) {
			throw new AbortedException("PRB is aborted: "+_abortReason+" : "+_abortDescription+" received "+_receivedCount+" of "+_packets+" on "+this);
		}
		return false;
	}
	
	public synchronized byte[] getBlock() throws AbortedException {
		if(allReceived()) return _data;
		throw new RuntimeException("Tried to get block before all packets received");
	}
	
	public synchronized Buffer getPacket(int x) throws AbortedException {
		if (_aborted) {
			throw new AbortedException("PRB is aborted");
		}
		if (!_received[x]) {
			throw new IllegalStateException("that packet is not received");
		}
		return new Buffer(_data, x * _packetSize, _packetSize);
	}
	

	public synchronized void removeListener(PacketReceivedListener listener) {
		_packetReceivedListeners.remove(listener);
	}

	/**
	 * Abort the transfer.
	 * @param reason
	 * @param description
	 * @param cancelledLocally If true, the transfer was deliberately aborted by *this node*,
	 * not as the result of a timeout.
	 * @return Null if the PRB is aborted now. The data if it is not.
	 */
	public byte[] abort(int reason, String description, boolean cancelledLocally) {
		PacketReceivedListener[] listeners;
		synchronized(this) {
			if(_aborted) {
				if(logMINOR) Logger.minor(this, "Already aborted "+this+" : reason="+_abortReason+" description="+_abortDescription);
				return null;
			}
			if(_receivedCount == _packets) {
				if(logMINOR) Logger.minor(this, "Already received");
				return _data;
			}
			Logger.normal(this, "Aborting PRB: "+reason+" : "+description+" on "+this, new Exception("debug"));
			_aborted = true;
			_abortedLocally = cancelledLocally;
			_abortReason = reason;
			_abortDescription = description;
			listeners = _packetReceivedListeners.toArray(new PacketReceivedListener[_packetReceivedListeners.size()]);
			_packetReceivedListeners.clear();
		}
		for (PacketReceivedListener prl : listeners) {
			prl.receiveAborted(reason, description);
		}
		return null;
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

	public boolean abortedLocally() {
		return _abortedLocally;
	}
}
