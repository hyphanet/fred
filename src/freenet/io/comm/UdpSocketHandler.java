package freenet.io.comm;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Random;

import org.tanukisoftware.wrapper.WrapperManager;

import freenet.io.comm.Peer.LocalAddressException;
import freenet.node.LoggingConfigHandler;
import freenet.node.Node;
import freenet.node.NodeInitException;
import freenet.support.FileLoggerHook;
import freenet.support.Logger;
import freenet.support.OOMHandler;

public class UdpSocketHandler extends Thread implements PacketSocketHandler {

	private final DatagramSocket _sock;
	private final InetAddress _bindTo;
	private IncomingPacketFilter lowLevelFilter;
	/** RNG for debugging, used with _dropProbability.
	 * NOT CRYPTO SAFE. DO NOT USE FOR THINGS THAT NEED CRYPTO SAFE RNG!
	 */
	private Random dropRandom;
	/** If >0, 1 in _dropProbability chance of dropping a packet; for debugging */
	private int _dropProbability;
	// Icky layer violation, but we need to know the Node to work around the EvilJVMBug.
	private final Node node;
	private static boolean logMINOR; 
	private volatile int lastTimeInSeconds;
	private boolean _isDone;
	private boolean _active = true;
	
	public UdpSocketHandler(int listenPort, InetAddress bindto, Node node) throws SocketException {
		super("MessageCore packet receiver thread on port " + listenPort);
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
			// Exit reasonably quickly
			_sock.setSoTimeout(1000);
		} catch (SocketException e) {
			throw new RuntimeException(e);
		}
//			}
		// Only used for debugging, no need to seed from Yarrow
		dropRandom = new Random();
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}

	/** Must be called, or we will NPE in run() */
	public void setLowLevelFilter(IncomingPacketFilter f) {
	    lowLevelFilter = f;
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
			synchronized(this) {
				if(!_active) return; // Finished
			}
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
		} else if(logMINOR) Logger.minor(this, "Null packet");
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

	public void start() {
		start(false);
	}
	
	public void start(boolean disableHangChecker) {
		lastTimeInSeconds = (int) (System.currentTimeMillis() / 1000);
		setDaemon(true);
		setPriority(Thread.MAX_PRIORITY);
		super.start();
		if(!disableHangChecker) {
			Thread checker = new Thread(new USMChecker(), "MessageCore$USMChecker");
			checker.setDaemon(true);
			checker.setPriority(Thread.MAX_PRIORITY);
			checker.start();
		}
	}
	
	public class USMChecker implements Runnable {
		public void run() {
			while(true) {
				if(_isDone) return; // don't synchronize because don't want to deadlock - this is our recovery mechanism
				logMINOR = Logger.shouldLog(Logger.MINOR, UdpSocketHandler.this);
				try {
					Thread.sleep(10*1000);
				} catch (InterruptedException e) {
					// Ignore
				}
				if(UdpSocketHandler.this.isAlive()) {
					if(logMINOR) Logger.minor(this, "PING on "+UdpSocketHandler.this);
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
							System.err.println("Restarting node: MessageCore froze for 3 minutes!");
						
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
					node.exit(NodeInitException.EXIT_MAIN_LOOP_LOST);
				}
			}
		}
	}

    public void close(boolean exit) {
    	Logger.error(this, "Closing.", new Exception("error"));
		synchronized (this) {
			_active = false;
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
    
	public int getDropProbability() {
		return _dropProbability;
	}
	
	public void setDropProbability(int dropProbability) {
		_dropProbability = dropProbability;
	}

    public int getPortNumber() {
        return _sock.getLocalPort();
    }

	public String toString() {
		return _sock.getLocalAddress() + ":" + _sock.getLocalPort();
	}

	public int getHeadersLength() {
		return UDP_HEADERS_LENGTH;
	}

}
