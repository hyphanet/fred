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

import org.tanukisoftware.wrapper.WrapperManager;

import freenet.io.comm.Peer.LocalAddressException;
import freenet.node.ByteCounter;
import freenet.node.LoggingConfigHandler;
import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.support.FileLoggerHook;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.TimeUtil;

public class UdpSocketManager extends Thread {

	public static final String VERSION = "$Id: UdpSocketManager.java,v 1.22 2005/08/25 17:28:19 amphibian Exp $";
	private static boolean logMINOR; 
	private Dispatcher _dispatcher;
	private final DatagramSocket _sock;
	/** _filters serves as lock for both */
	private final LinkedList _filters = new LinkedList();
	private final LinkedList _unclaimed = new LinkedList();
	private int _dropProbability;
	private IncomingPacketFilter lowLevelFilter;
	/** RNG for debugging, used with _dropProbability.
	 * NOT CRYPTO SAFE. DO NOT USE FOR THINGS THAT NEED CRYPTO SAFE RNG!
	 */
	private Random dropRandom;
	private boolean _isDone;
	private static UdpSocketManager _usm;
	private static final int MAX_UNMATCHED_FIFO_SIZE = 50000;
	private static final long MAX_UNCLAIMED_FIFO_ITEM_LIFETIME = 60*60*1000;  // 1 hour
	private volatile int lastTimeInSeconds;
	private final InetAddress _bindTo;

	// Icky layer violation, but we need to know the Node to work around the EvilJVMBug.
	private final Node node;
	
	public void start() {
		start(false);
	}
	
	public void start(boolean disableHangChecker) {
		lastTimeInSeconds = (int) (System.currentTimeMillis() / 1000);
		setDaemon(true);
		setPriority(Thread.MAX_PRIORITY);
		super.start();
		if(!disableHangChecker) {
			Thread checker = new Thread(new USMChecker(), "UdpSocketManager$USMChecker");
			checker.setDaemon(true);
			checker.setPriority(Thread.MAX_PRIORITY);
			checker.start();
		}
	}
	
	public class USMChecker implements Runnable {
		public void run() {
			while(true) {
				logMINOR = Logger.shouldLog(Logger.MINOR, UdpSocketManager.this);
				try {
					Thread.sleep(10*1000);
				} catch (InterruptedException e) {
					// Ignore
				}
				if(UdpSocketManager.this.isAlive()) {
					if(logMINOR) Logger.minor(this, "PING on "+UdpSocketManager.this);
					long time = System.currentTimeMillis();
					int timeSecs = (int) (time / 1000);
					if(timeSecs - lastTimeInSeconds > 3*60) {
						
						// USM has hung.
						// Probably caused by the EvilJVMBug (see PacketSender).
						// We'd better restart... :(
						
						LoggingConfigHandler lch = Node.logConfigHandler;
						FileLoggerHook flh = lch == null ? null : lch.getFileLoggerHook();
						boolean hasRedirected = flh == null ? false : flh.hasRedirectedStdOutErrNoLock();
						
						if(!hasRedirected)
							System.err.println("Restarting node: UdpSocketManager froze for 3 minutes!");
						
						try {
							if(node.isUsingWrapper()){
								WrapperManager.requestThreadDump();
								WrapperManager.restart();
							}else{
								if(!hasRedirected)
									System.err.println("Exiting on deadlock, but not running in the wrapper! Please restart the node manually.");
								
								// No wrapper : we don't want to let it harm the network!
								node.exit("USM deadlock");
							}
						} catch (Throwable t) {
							if(!hasRedirected) {
								System.err.println("Error : can't restart the node : consider installing the wrapper. PLEASE REPORT THAT ERROR TO devl@freenetproject.org");
								t.printStackTrace();
							}
							node.exit("USM deadlock and error");
						}
					}
				} else {
					Logger.error(this, "MAIN LOOP TERMINATED");
					System.err.println("MAIN LOOP TERMINATED!");
					node.exit(Node.EXIT_MAIN_LOOP_LOST);
				}
			}
		}
	}

	public UdpSocketManager(int listenPort, InetAddress bindto, Node node) throws SocketException {
		super("UdpSocketManager packet receiver thread on port " + listenPort);
		this.node = node;
		_bindTo = bindto;
		    // Keep the Updater code in, just commented out, for now
		    // We may want to be able to do on-line updates.
//			if (Updater.hasResource()) {
//				_sock = (DatagramSocket) Updater.getResource();
//			} else {
		_sock = new DatagramSocket(listenPort, bindto);
		int sz = _sock.getReceiveBufferSize();
		if(sz < 32768)
			_sock.setReceiveBufferSize(32768);
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
//			}
		// Only used for debugging, no need to seed from Yarrow
		dropRandom = new Random();
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}

	public InetAddress getBindTo() {
		return _bindTo;
	}
	
	public void run() { // Listen for packets
		try {
			runLoop();
		} catch (Throwable t) {
			// Impossible? It keeps on exiting. We get the below,
			// but not this...
			try {
				System.err.print(t.getClass().getName());
				System.err.println();
			} catch (Throwable tt) {};
			try {
				System.err.print(t.getMessage());
				System.err.println();
			} catch (Throwable tt) {};
			try {
				System.gc();
				System.runFinalization();
				System.gc();
				System.runFinalization();
			} catch (Throwable tt) {}
			try {
				Runtime r = Runtime.getRuntime();
				System.err.print(r.freeMemory());
				System.err.println();
				System.err.print(r.totalMemory());
				System.err.println();
			} catch (Throwable tt) {};
			try {
				t.printStackTrace();
			} catch (Throwable tt) {};
		} finally {
			System.err.println("run() exiting");
			Logger.error(this, "run() exiting");
			synchronized (this) {
				_isDone = true;
				notifyAll();
			}
		}
	}

	private void runLoop() {
		byte[] buf = new byte[MAX_RECEIVE_SIZE];
		DatagramPacket packet = new DatagramPacket(buf, buf.length);
		while (/*_active*/true) {
			try {
				lastTimeInSeconds = (int) (System.currentTimeMillis() / 1000);
				realRun(packet);
            } catch (OutOfMemoryError e) {
				OOMHandler.handleOOM(e);
				System.err.println("Will retry above failed operation...");
			} catch (Throwable t) {
				System.err.println("Caught "+t);
				t.printStackTrace(System.err);
				Logger.error(this, "Caught " + t, t);
			}
		}
	}
	
	private void realRun(DatagramPacket packet) {
		// Single receiving thread
		boolean gotPacket = getPacket(packet);
		// Check for timedout _filters
		removeTimedOutFilters();
		if (gotPacket) {
			long startTime = System.currentTimeMillis();
			Peer peer = new Peer(packet.getAddress(), packet.getPort());
			long endTime = System.currentTimeMillis();
			if(endTime - startTime > 50) {
				if(endTime-startTime > 3000)
					Logger.error(this, "packet creation took "+(endTime-startTime)+"ms");
				else
					if(logMINOR) Logger.minor(this, "packet creation took "+(endTime-startTime)+"ms");
			}
			byte[] data = packet.getData();
			int offset = packet.getOffset();
			int length = packet.getLength();
			if (lowLevelFilter != null) {
				try {
					if(logMINOR) Logger.minor(this, "Processing packet of length "+length+" from "+peer);
					startTime = System.currentTimeMillis();
					lowLevelFilter.process(data, offset, length, peer);
					endTime = System.currentTimeMillis();
					if(endTime - startTime > 50) {
						if(endTime-startTime > 3000)
							Logger.error(this, "processing packet took "+(endTime-startTime)+"ms");
						else
							if(logMINOR) Logger.minor(this, "processing packet took "+(endTime-startTime)+"ms");
					}
					if(logMINOR) Logger.minor(this,
							"Successfully handled packet length " + length);
				} catch (Throwable t) {
					Logger.error(this, "Caught " + t + " from "
							+ lowLevelFilter, t);
				}
			} else {
				// Create a bogus context since no filter
				Message m = decodeSingleMessage(data, offset, length,
						new DummyPeerContext(peer), 0);
				if (m != null) {
					checkFilters(m);
				}
			}
		} else if(logMINOR) Logger.minor(this, "Null packet");
	}
	
	/**
	 * Decode a packet from data and a peer.
	 * Can be called by IncomingPacketFilter's.
     * @param data
     * @param offset
     * @param length
     * @param peer
     */
    public Message decodeSingleMessage(byte[] data, int offset, int length, PeerContext peer, int overhead) {
        try {
            return Message.decodeMessageFromPacket(data, offset, length, peer, overhead);
        } catch (Throwable t) {
            Logger.error(this, "Could not decode packet: "+t, t);
            return null;
        }
    }

    // FIXME necessary to deal with bugs around build 1000; arguably necessary to deal with large node names in connection setup
    // Revert to 1500?
    private static final int MAX_RECEIVE_SIZE = 2048;
    
    private boolean getPacket(DatagramPacket packet) {
		try {
			_sock.receive(packet);
			// TODO: keep?
			IOStatisticCollector.addInfo(packet.getAddress() + ":" + packet.getPort(),
					packet.getLength(), 0);
		} catch (SocketTimeoutException e1) {
			return false;
		} catch (IOException e2) {
			throw new RuntimeException(e2);
		}
		if(logMINOR) Logger.minor(this, "Received packet");
		return true;
	}

	private void removeTimedOutFilters() {
		long tStart = System.currentTimeMillis();
		synchronized (_filters) {
			for (ListIterator i = _filters.listIterator(); i.hasNext();) {
				MessageFilter f = (MessageFilter) i.next();
				if (f.timedOut()) {
					f.setMessage(null);
					synchronized (f) {
						i.remove();
						f.notifyAll();
					}
				} else { // Because _filters are in order of timeout, we
					// can abort the iteration as soon as we find one that
					// doesn't timeout
					break;
				}
			}
		}
		long tEnd = System.currentTimeMillis();
		if(tEnd - tStart > 50) {
			if(tEnd - tStart > 3000)
				Logger.error(this, "removeTimedOutFilters took "+(tEnd-tStart)+"ms");
			else
				if(logMINOR) Logger.minor(this, "removeTimedOutFilters took "+(tEnd-tStart)+"ms");
		}
	}

	/**
	 * Dispatch a message to a waiting filter, or feed it to the
	 * Dispatcher if none are found.
	 * @param m The Message to dispatch.
	 */
	public void checkFilters(Message m) {
		long tStart = System.currentTimeMillis();
		if(logMINOR) Logger.minor(this, "checkFilters: "+m+" from "+m.getSource());
		if ((m.getSource()) instanceof PeerNode)
		{
			((PeerNode)m.getSource()).addToLocalNodeReceivedMessagesFromStatistic(m);
		}
		boolean matched = false;
		if ((!(m.getSpec().equals(DMT.packetTransmit))) && logMINOR) {
			if ((m.getSpec().equals(DMT.ping) || m.getSpec().equals(DMT.pong)) && Logger.shouldLog(Logger.DEBUG, this)) {
				Logger.debug(this, "" + (System.currentTimeMillis() % 60000) + ' ' + _sock.getLocalPort() + " <- "
						+ m.getSource() + " : " + m);
			} else {
				if(logMINOR) Logger.minor(this, "" + (System.currentTimeMillis() % 60000) + ' ' + _sock.getLocalPort() + " <- "
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
						f.notifyAll();
					}
					if(logMINOR) Logger.minor(this, "Matched: "+f);
					break; // Only one match permitted per message
				}
			}
		}
		// Feed unmatched messages to the dispatcher
		if ((!matched) && (_dispatcher != null)) {
		    try {
		    	if(logMINOR) Logger.minor(this, "Feeding to dispatcher: "+m);
		        matched = _dispatcher.handleMessage(m);
		    } catch (Throwable t) {
		        Logger.error(this, "Dispatcher threw "+t, t);
		    }
		}
		// Keep the last few _unclaimed messages around in case the intended receiver isn't receiving yet
		if (!matched) {
			if(logMINOR) Logger.minor(this, "Unclaimed: "+m);
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
				if(logMINOR) Logger.minor(this, "Rechecking filters and adding message");
				for (ListIterator i = _filters.listIterator(); i.hasNext();) {
					MessageFilter f = (MessageFilter) i.next();
					if (f.match(m)) {
						matched = true;
						f.setMessage(m);
						synchronized (f) {
							i.remove();
							f.notifyAll();
						}
						if(logMINOR) Logger.minor(this, "Matched: "+f);
						break; // Only one match permitted per message
					}
				}
				if(!matched) {
				    while (_unclaimed.size() > MAX_UNMATCHED_FIFO_SIZE) {
				        Message removed = (Message)_unclaimed.removeFirst();
				        long messageLifeTime = System.currentTimeMillis() - removed.localInstantiationTime;
				        if ((removed.getSource()) instanceof PeerNode) {
				            Logger.normal(this, "Dropping unclaimed from "+removed.getSource().getPeer()+", lived "+TimeUtil.formatTime(messageLifeTime, 2, true)+" (quantity)"+": "+removed);
				        } else {
				            Logger.normal(this, "Dropping unclaimed, lived "+TimeUtil.formatTime(messageLifeTime, 2, true)+" (quantity)"+": "+removed);
				        }
				    }
				    _unclaimed.addLast(m);
				    if(logMINOR) Logger.minor(this, "Done");
				}
			}
		}
		long tEnd = System.currentTimeMillis();
		if(tEnd - tStart > 50) {
			if(tEnd - tStart > 3000)
				Logger.error(this, "checkFilters took "+(tEnd-tStart)+"ms with unclaimedFIFOSize of "+_unclaimed.size()+" for matched: "+matched);
			else
				if(logMINOR) Logger.minor(this, "checkFilters took "+(tEnd-tStart)+"ms with unclaimedFIFOSize of "+_unclaimed.size()+" for matched: "+matched);
		}
	}
	
	/** IncomingPacketFilter should call this when a node is disconnected. */
	public void onDisconnect(PeerContext ctx) {
	    synchronized(_filters) {
			ListIterator i = _filters.listIterator();
			while (i.hasNext()) {
			    MessageFilter f = (MessageFilter) i.next();
			    if(f.matchesDroppedConnection() && (f._source == ctx)) {
			        f.onDroppedConnection(ctx);
			        if(f.droppedConnection() != null) {
			            synchronized(f) {
			                f.notifyAll();
			            }
			        }
			    }
			}
	    }
	}
	
	public Message waitFor(MessageFilter filter, ByteCounter ctr) throws DisconnectedException {
		boolean logDEBUG = Logger.shouldLog(Logger.DEBUG, this);
		if(logDEBUG) Logger.debug(this, "Waiting for "+filter);
		long startTime = System.currentTimeMillis();
		Message ret = null;
		if((lowLevelFilter != null) && (filter._source != null) && 
		        filter.matchesDroppedConnection() &&
		        lowLevelFilter.isDisconnected(filter._source))
		    throw new DisconnectedException();
		// Check to see whether the filter matches any of the recently _unclaimed messages
		// Drop any _unclaimed messages that the filter doesn't match that are also older than MAX_UNCLAIMED_FIFO_ITEM_LIFETIME
		long now = System.currentTimeMillis();
		long messageDropTime = now - MAX_UNCLAIMED_FIFO_ITEM_LIFETIME;
		long messageLifeTime = 0;
		synchronized (_filters) {
			if(logMINOR) Logger.minor(this, "Checking _unclaimed");
			for (ListIterator i = _unclaimed.listIterator(); i.hasNext();) {
				Message m = (Message) i.next();
				if (filter.match(m)) {
					i.remove();
					ret = m;
					if(logMINOR) Logger.debug(this, "Matching from _unclaimed");
					break;
				} else if (m.localInstantiationTime < messageDropTime) {
					i.remove();
					messageLifeTime = now - m.localInstantiationTime;
					if ((m.getSource()) instanceof PeerNode) {
						Logger.normal(this, "Dropping unclaimed from "+m.getSource().getPeer()+", lived "+TimeUtil.formatTime(messageLifeTime, 2, true)+" (age)"+": "+m);
					} else {
						Logger.normal(this, "Dropping unclaimed, lived "+TimeUtil.formatTime(messageLifeTime, 2, true)+" (age)"+": "+m);
					}
				}
			}
			if (ret == null) {
				if(logMINOR) Logger.minor(this, "Not in _unclaimed");
			    // Insert filter into filter list in order of timeout
				ListIterator i = _filters.listIterator();
				while (true) {
					if (!i.hasNext()) {
						i.add(filter);
						if(logMINOR) Logger.minor(this, "Added at end");
						break;
					}
					MessageFilter mf = (MessageFilter) i.next();
					if (mf.getTimeout() > filter.getTimeout()) {
						i.previous();
						i.add(filter);
						if(logMINOR) Logger.minor(this, "Added in middle - mf timeout="+mf.getTimeout()+" - my timeout="+filter.getTimeout());
						break;
					}
				}
			}
		}
		long tEnd = System.currentTimeMillis();
		if(tEnd - now > 50) {
			if(tEnd - now > 3000)
				Logger.error(this, "waitFor _unclaimed iteration took "+(tEnd-now)+"ms with unclaimedFIFOSize of "+_unclaimed.size()+" for ret of "+ret);
			else
				if(logMINOR) Logger.minor(this, "waitFor _unclaimed iteration took "+(tEnd-now)+"ms with unclaimedFIFOSize of "+_unclaimed.size()+" for ret of "+ret);
		}
		// Unlock to wait on filter
		// Waiting on the filter won't release the outer lock
		// So we have to release it here
		if(ret == null) {	
			if(logMINOR) Logger.minor(this, "Waiting...");
			synchronized (filter) {
				try {
					// Precaution against filter getting matched between being added to _filters and
					// here - bug discovered by Mason
				    boolean fmatched = false;
				    while(!(fmatched = (filter.matched() || (filter.droppedConnection() != null)))) {
				        long wait = filter.getTimeout()-System.currentTimeMillis();
				        if(wait > 0)
				            filter.wait(wait);
				        else break;
					}
				    if(filter.droppedConnection() != null)
				        throw new DisconnectedException();
				    if(logMINOR) Logger.minor(this, "Matched: "+fmatched);
				} catch (InterruptedException e) {
				}
				ret = filter.getMessage();
				filter.clearMatched();
			}
			if(logDEBUG) Logger.debug(this, "Returning "+ret+" from "+filter);
		}
		// Probably get rid...
//		if (Dijjer.getDijjer().getDumpMessageWaitTimes() != null) {
//			Dijjer.getDijjer().getDumpMessageWaitTimes().println(filter.toString() + "\t" + filter.getInitialTimeout() + "\t"
//					+ (System.currentTimeMillis() - startTime));
//			Dijjer.getDijjer().getDumpMessageWaitTimes().flush();
//		}
		long endTime = System.currentTimeMillis();
		if(logDEBUG) Logger.debug(this, "Returning in "+(endTime-startTime)+"ms");
		if((ctr != null) && (ret != null))
			ctr.receivedBytes(ret._receivedByteCount);
		return ret;
	}

	/**
	 * Send a Message to a PeerContext.
	 * @throws NotConnectedException If we are not currently connected to the node.
	 */
	public void send(PeerContext destination, Message m, ByteCounter ctr) throws NotConnectedException {
	    if(m.getSpec().isInternalOnly()) {
	        Logger.error(this, "Trying to send internal-only message "+m+" of spec "+m.getSpec(), new Exception("debug"));
	        return;
	    }
		if ((m.getSpec().equals(DMT.ping) || m.getSpec().equals(DMT.pong)) && logMINOR) {
			if(Logger.shouldLog(Logger.DEBUG, this))
				Logger.debug(this, "" + (System.currentTimeMillis() % 60000) + ' ' + _sock.getPort() + " -> " + destination
						+ " : " + m);
		} else {
			if(logMINOR) Logger.minor(this, "" + (System.currentTimeMillis() % 60000) + ' ' + _sock.getPort() + " -> " + destination
					+ " : " + m);
		}
//		byte[] blockToSend = m.encodeToPacket(lowLevelFilter, destination);
//		if(lowLevelFilter != null) {
//			try {
//				lowLevelFilter.processOutgoing(blockToSend, 0, blockToSend.length, destination);
//				return;
//			} catch (IncomingPacketFilterException t) {
//				Logger.error(this, "Caught "+t+" sending "+m+" to "+destination, t);
//				destination.forceDisconnect();
//				throw new NotConnectedException("Error "+t.toString()+" forced disconnect");
//			}
//		} else {
//		    sendPacket(blockToSend, destination.getPeer());
//		}
		((PeerNode)destination).sendAsync(m, null, 0, ctr);
	}

	/**
	 * Send a block of encoded bytes to a peer. This is called by
	 * send, and by IncomingPacketFilter.processOutgoing(..).
     * @param blockToSend The data block to send.
     * @param destination The peer to send it to.
     */
    public void sendPacket(byte[] blockToSend, Peer destination, boolean allowLocalAddresses) throws LocalAddressException {
    	assert(blockToSend != null);
		// there should be no DNS needed here, but go ahead if we can, but complain doing it
		if( destination.getAddress(false, allowLocalAddresses) == null ) {
  			Logger.error(this, "Tried sending to destination without pre-looked up IP address(needs a real Peer.getHostname()): null:" + destination.getPort(), new Exception("error"));
			if( destination.getAddress(true, allowLocalAddresses) == null ) {
  				Logger.error(this, "Tried sending to bad destination address: null:" + destination.getPort(), new Exception("error"));
  				return;
  			}
  		}
		if (_dropProbability > 0) {
			if (dropRandom.nextInt() % _dropProbability == 0) {
				if(logMINOR) Logger.minor(this, "DROPPED: " + _sock.getLocalPort() + " -> " + destination.getPort());
				return;
			}
		}
		InetAddress address = destination.getAddress(false, allowLocalAddresses);
		assert(address != null);
		int port = destination.getPort();
		DatagramPacket packet = new DatagramPacket(blockToSend, blockToSend.length);
		packet.setAddress(address);
		packet.setPort(port);
		
		// TODO: keep?
		// packet.length() is simply the size of the buffer, it knows nothing of UDP headers
		IOStatisticCollector.addInfo(address + ":" + port, 0, blockToSend.length + UDP_HEADERS_LENGTH); 
		
		try {
			_sock.send(packet);
		} catch (IOException e) {
			if(packet.getAddress() instanceof Inet6Address)
				Logger.normal(this, "Error while sending packet to IPv6 address: "+destination+": "+e, e);
			else
				Logger.error(this, "Error while sending packet to " + destination+": "+e, e);
		}
    }

    public void close(boolean exit) {
    	Logger.error(this, "Closing.", new Exception("error"));
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

	public void setLowLevelFilter(IncomingPacketFilter f) {
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

//	public static void init(int externalListenPort, InetAddress bindto)
//		throws SocketException
//	{
//		_usm = new UdpSocketManager(externalListenPort, bindto);
//	}
//
    public int getPortNumber() {
        return _sock.getLocalPort();
    }

    
	// CompuServe use 1400 MTU; AOL claim 1450; DFN@home use 1448.
	// http://info.aol.co.uk/broadband/faqHomeNetworking.adp
	// http://www.compuserve.de/cso/hilfe/linux/hilfekategorien/installation/contentview.jsp?conid=385700
	// http://www.studenten-ins-netz.net/inhalt/service_faq.html
	// officially GRE is 1476 and PPPoE is 1492.
	// unofficially, PPPoE is often 1472 (seen in the wild). Also PPPoATM is sometimes 1472.
    static final int MAX_ALLOWED_MTU = 1400;
    // FIXME this is different for IPv6 (check all uses of constant when fixing)
    public static final int UDP_HEADERS_LENGTH = 28;
    
    /**
     * @return The maximum packet size supported by this SocketManager, not including transport (UDP/IP) headers.
     */
    public int getMaxPacketSize() { //FIXME: what about passing a peerNode though and doing it on a per-peer basis?
    	final int minAdvertisedMTU = node.ipDetector.getMinimumDetectedMTU();
    	
    	// We don't want the MTU detection thingy to prevent us to send PacketTransmits!
    	if(minAdvertisedMTU < 1100){
    		Logger.error(this, "It shouldn't happen : we disabled the MTU detection algorithm because the advertised MTU is smallish !! ("+node.ipDetector.getMinimumDetectedMTU()+')'); 
    		return MAX_ALLOWED_MTU - UDP_HEADERS_LENGTH;
    	} else
    		return (minAdvertisedMTU < MAX_ALLOWED_MTU ? minAdvertisedMTU : MAX_ALLOWED_MTU) - UDP_HEADERS_LENGTH;
    	// UDP/IP header is 28 bytes.
    }

	/**
	 * @return the number of received messages that are currently unclaimed
	 */
	public int getUnclaimedFIFOSize() {
		synchronized (_filters){
			return _unclaimed.size();
		}
	}
	
	public Map getUnclaimedFIFOMessageCounts() {
		Map messageCounts = new HashMap();
		synchronized(_filters) {
			for (ListIterator i = _unclaimed.listIterator(); i.hasNext();) {
				Message m = (Message) i.next();
				String messageName = m.getSpec().getName();
				Integer messageCount = (Integer) messageCounts.get(messageName);
				if (messageCount == null) {
					messageCounts.put(messageName, new Integer(1) );
				} else {
					messageCount = new Integer(messageCount.intValue() + 1);
					messageCounts.put(messageName, messageCount );
				}
			}
		}
		return messageCounts;
	}

	public int getDropProbability() {
		return _dropProbability;
	}
}
