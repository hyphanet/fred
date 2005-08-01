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
import java.util.*;

import freenet.support.Logger;

public class UdpSocketManager extends Thread {

	public static final String VERSION = "$Id: UdpSocketManager.java,v 1.13 2005/08/01 19:20:12 amphibian Exp $";
	private Dispatcher _dispatcher;
	private DatagramSocket _sock;
	/** _filters serves as lock for both */
	private LinkedList _filters = new LinkedList();
	private LinkedList _unclaimed = new LinkedList();
	private int _unclaimedPos = 0;
	private int _dropProbability = 0;
	private LowLevelFilter lowLevelFilter;
	/** RNG for debugging, used with _dropProbability.
	 * NOT CRYPTO SAFE. DO NOT USE FOR THINGS THAT NEED CRYPTO SAFE RNG!
	 */
	private Random dropRandom;
	private boolean _active = true;
	private boolean _isDone = false;
	private static UdpSocketManager _usm;

	protected UdpSocketManager() {
	}

	public UdpSocketManager(int listenPort) throws SocketException {
		super("UdpSocketManager sender thread on port " + listenPort);
		try {
		    // Keep the Updater code in, just commented out, for now
		    // We may want to be able to do on-line updates.
//			if (Updater.hasResource()) {
//				_sock = (DatagramSocket) Updater.getResource();
//			} else {
				_sock = new DatagramSocket(listenPort);
//			}
		} catch (BindException e) {
			Logger.fatal(UdpSocketManager.class, -1, "Couldn't connect to UDP port " + listenPort + ", is another instance of Dijjer running?");
		}
		// Only used for debugging, no need to seed from Yarrow
		dropRandom = new Random();
		start();
	}

	public void run() { // Listen for packets
		while (_active) {
			DatagramPacket packet = getPacket();
			// Check for timedout _filters
			removeTimedOutFilters();
			// Check for matched _filters
			if(packet != null) {
			    Peer peer = new Peer(packet.getAddress(), packet.getPort());
			    byte[] data = packet.getData();
			    int offset = packet.getOffset();
			    int length = packet.getLength();
			    if(lowLevelFilter != null)
			        lowLevelFilter.process(data, offset, length, peer);
			    else {
			        // Create a bogus context since no filter
			        Message m = decodePacket(data, offset, length, new DummyPeerContext(peer));
			        if(m != null)
			            checkFilters(m);
			    }
			}
		}
		synchronized (this) {
			_isDone = true;
			notifyAll();
		}
	}

	/**
	 * Decode a packet from data and a peer.
	 * Can be called by LowLevelFilter's.
     * @param data
     * @param offset
     * @param length
     * @param peer
     */
    public Message decodePacket(byte[] data, int offset, int length, PeerContext peer) {
        try {
            return Message.decodeFromPacket(data, offset, length, peer);
        } catch (Throwable t) {
            Logger.error(this, "Could not decode packet: "+t, t);
            return null;
        }
    }

    private DatagramPacket getPacket() {
		try {
			// We make it timeout every 100ms so that we can check for
			// _filters which have timed out, this
			// is ugly but our only option without resorting to java.nio
			// because there is no way to forcefully
			// interrupt a socket wait operation
			_sock.setSoTimeout(100);
		} catch (SocketException e) {
			throw new RuntimeException(e);
		}
		// TODO: Avoid recreating the packet each time (warning, there are some issues reusing DatagramPackets, be
		// careful)
		DatagramPacket packet = new DatagramPacket(new byte[1500], 1500);
		try {
			_sock.receive(packet);
		} catch (SocketTimeoutException e1) {
			packet = null;
		} catch (IOException e2) {
			throw new RuntimeException(e2);
		}
		return packet;
	}

	private void removeTimedOutFilters() {
		synchronized (_filters) {
			for (ListIterator i = _filters.listIterator(); i.hasNext();) {
				MessageFilter f = (MessageFilter) i.next();
				if (f.timedOut()) {
					f.setMessage(null);
					synchronized (f) {
						i.remove();
						f.notify();
					}
				} else { // Because _filters are in order of timeout, we
					// can abort the iteration as soon as we find one that
					// doesn't timeout
					break;
				}
			}
		}
	}

	/**
	 * Dispatch a message to a waiting filter, or feed it to the
	 * Dispatcher if none are found.
	 * @param m The Message to dispatch.
	 */
	public void checkFilters(Message m) {
		boolean matched = false;
		if (!(m.getSpec().equals(DMT.packetTransmit))) {
			if (m.getSpec().equals(DMT.ping) || m.getSpec().equals(DMT.pong)) {
				Logger.debug(this, "" + (System.currentTimeMillis() % 60000) + " " + _sock.getLocalPort() + " <- "
						+ m.getSource() + " : " + m);
			} else {
				Logger.minor(this, "" + (System.currentTimeMillis() % 60000) + " " + _sock.getLocalPort() + " <- "
						+ m.getSource() + " : " + m);
			}
		}
		synchronized (_filters) {
			for (ListIterator i = _filters.listIterator(); i.hasNext();) {
				MessageFilter f = (MessageFilter) i.next();
				if (f.match(m)) {
					matched = true;
					f.setMessage(m);
					synchronized (f) {
						i.remove();
						f.notify();
					}
					Logger.minor(this, "Matched");
					break; // Only one match permitted per message
				}
			}
		}
		// Feed unmatched messages to the dispatcher
		if (!matched && (_dispatcher != null)) {
		    try {
		        Logger.minor(this, "Feeding to dispatcher: "+m);
		        matched = _dispatcher.handleMessage(m);
		    } catch (Throwable t) {
		        Logger.error(this, "Dispatcher threw "+t, t);
		    }
		}
		// Keep the last few _unclaimed messages around in case the intended receiver isn't receiving yet
		if (!matched) {
		    Logger.minor(this, "Unclaimed: "+m);
		    /** Check filters and then add to _unmatched is ATOMIC
		     * It has to be atomic, because otherwise we can get a
		     * race condition that results in timeouts on MFs.
		     * 
		     * Specifically:
		     * - Thread A receives packet
		     * - Thread A checks filters. It doesn't match any.
		     * - Thread A feeds to Dispatcher.
		     * - Thread B creates filter.
		     * - Thread B checks _unmatched.
		     * - Thread B adds filter.
		     * - Thread B sleeps.
		     * - Thread A returns from Dispatcher. Which didn't match.
		     * - Thread A adds to _unmatched.
		     * 
		     * OOPS!
		     * The only way to fix this is to have checking the
		     * filters and unmatched be a single atomic operation.
		     * Another race is possible if we merely recheck the
		     * filters after we return from dispatcher, for example.
		     */
			synchronized (_filters) {
				for (ListIterator i = _filters.listIterator(); i.hasNext();) {
					MessageFilter f = (MessageFilter) i.next();
					if (f.match(m)) {
						matched = true;
						f.setMessage(m);
						synchronized (f) {
							i.remove();
							f.notify();
						}
						break; // Only one match permitted per message
					}
				}
				if(!matched) {
				    while (_unclaimed.size() > 500) {
				        Message removed = (Message)_unclaimed.removeFirst();
				        Logger.normal(this, "Unclaimed: "+removed);
				    }
				    _unclaimed.addLast(m);
				}
			}
		}
	}
	
	public Message waitFor(MessageFilter filter) {
		long startTime = System.currentTimeMillis();
		Message ret = null;
		// Check to see whether the filter matches any of the recently _unclaimed messages
		synchronized (_filters) {
			Logger.debug(this, "Checking _unclaimed");
			for (ListIterator i = _unclaimed.listIterator(); i.hasNext();) {
				Message m = (Message) i.next();
				if (filter.match(m)) {
					i.remove();
					ret = m;
					Logger.debug(this, "Matching from _unclaimed");
				}
			}
			Logger.debug(this, "Not in _unclaimed");
			if (ret == null) {
			    // Insert filter into filter list in order of timeout
				ListIterator i = _filters.listIterator();
				while (true) {
					if (!i.hasNext()) {
						i.add(filter);
						Logger.debug(this, "Added at end");
						break;
					}
					MessageFilter mf = (MessageFilter) i.next();
					if (mf.getTimeout() > filter.getTimeout()) {
						i.previous();
						i.add(filter);
						Logger.debug(this, "Added in middle - mf timeout="+mf.getTimeout()+" - my timeout="+filter.getTimeout());
						break;
					}
				}
			}
		}
		// Unlock to wait on filter
		// Waiting on the filter won't release the outer lock
		// So we have to release it here
		if(ret == null) {	
			Logger.debug(this, "Waiting...");
			synchronized (filter) {
				try {
					// Precaution against filter getting matched between being added to _filters and
					// here - bug discovered by Mason
				    boolean fmatched = false;
				    while(!(fmatched = filter.matched())) {
				        long wait = filter.getTimeout()-System.currentTimeMillis();
				        if(wait > 0)
				            filter.wait(wait);
				        else break;
					}
				    Logger.minor(this, "Matched: "+fmatched);
				} catch (InterruptedException e) {
				}
				ret = filter.getMessage();
				filter.clearMatched();
			}
			Logger.debug(this, "Returning "+ret+" from "+filter);
		}
		// Probably get rid...
//		if (Dijjer.getDijjer().getDumpMessageWaitTimes() != null) {
//			Dijjer.getDijjer().getDumpMessageWaitTimes().println(filter.toString() + "\t" + filter.getInitialTimeout() + "\t"
//					+ (System.currentTimeMillis() - startTime));
//			Dijjer.getDijjer().getDumpMessageWaitTimes().flush();
//		}
		long endTime = System.currentTimeMillis();
		Logger.debug(this, "Returning in "+(endTime-startTime)+"ms");
		return ret;
	}

	public void send(PeerContext destination, Message m) {
	    if(m.getSpec().isInternalOnly()) {
	        Logger.error(this, "Trying to send internal-only message "+m+" of spec "+m.getSpec(), new Exception("debug"));
	        return;
	    }
		if (m.getSpec().equals(DMT.ping) || m.getSpec().equals(DMT.pong)) {
			Logger.debug(this, "" + (System.currentTimeMillis() % 60000) + " " + _sock.getPort() + " -> " + destination
					+ " : " + m);
		} else {
			Logger.minor(this, "" + (System.currentTimeMillis() % 60000) + " " + _sock.getPort() + " -> " + destination
					+ " : " + m);
		}
		byte[] blockToSend = m.encodeToPacket(lowLevelFilter, destination);
		if(lowLevelFilter != null) {
		    lowLevelFilter.processOutgoing(blockToSend, 0, blockToSend.length, destination);
		} else {
		    sendPacket(blockToSend, destination.getPeer());
		}
	}

	/**
	 * Send a block of encoded bytes to a peer. This is called by
	 * send, and by LowLevelFilter.processOutgoing(..).
     * @param blockToSend The data block to send.
     * @param destination The peer to send it to.
     */
    public void sendPacket(byte[] blockToSend, Peer destination) {
		if (_dropProbability > 0) {
			if (dropRandom.nextInt() % _dropProbability == 0) {
				Logger.minor(this, "DROPPED: " + _sock.getLocalPort() + " -> " + destination.getPort());
				return;
			}
		}
		DatagramPacket packet = new DatagramPacket(blockToSend, blockToSend.length);
		packet.setAddress(destination.getAddress());
		packet.setPort(destination.getPort());
		try {
			_sock.send(packet);
		} catch (IOException e) {
			Logger.error(this, "Error while sending packet to " + destination, e);
		}
    }

    public void close(boolean exit) {
		_active = false;
		synchronized (this) {
			while (!_isDone) {
				try {
					wait(2000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
		if (exit) {
			_sock.close();
		} else {
		    Logger.fatal(this, 10, "Not implemented: close(false)");
			//Updater.saveResource(_sock);
		}
	}

	public void setDispatcher(Dispatcher d) {
		_dispatcher = d;
	}

	public void setLowLevelFilter(LowLevelFilter f) {
	    lowLevelFilter = f;
	}
	
	public String toString() {
		return _sock.getLocalAddress() + ":" + _sock.getLocalPort();
	}

	public void setDropProbability(int dropProbability) {
		_dropProbability = dropProbability;
	}

	public static UdpSocketManager getUdpSocketManager()
	{
		return _usm;
	}

	public static void init(int externalListenPort)
		throws SocketException
	{
		_usm = new UdpSocketManager(externalListenPort);
	}

    public int getPortNumber() {
        return _sock.getLocalPort();
    }
}