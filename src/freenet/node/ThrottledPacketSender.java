package freenet.node;

import java.util.LinkedList;

import freenet.io.comm.Message;
import freenet.io.comm.NotConnectedException;
import freenet.support.Logger;

/**
 * Sends throttled packets.
 * Only data packets are throttled, and they are all
 * slightly over 1kB. So we can throttle bandwidth by
 * just sending no more than N of these per second.
 * Initially a rather brutal implementation; we send one
 * packet, every 1/N seconds, or we don't. That's it!
 */
public class ThrottledPacketSender implements Runnable {

	final LinkedList queuedPackets;
	final int sleepTime;
	
	public ThrottledPacketSender(int sleepTime) {
		this.sleepTime = sleepTime;
		queuedPackets = new LinkedList();
		Thread t = new Thread(this, "Throttled packet sender");
		t.setDaemon(true);
		t.start();
	}
	
	public class ThrottledPacket {
		public ThrottledPacket(Message msg2, PeerNode pn2) {
			this.msg = msg2;
			this.pn = pn2;
			sent = false;
			queuedTime = System.currentTimeMillis();
		}
		
		final Message msg;
		final PeerNode pn;
		final long queuedTime;
		boolean sent;
		boolean lostConn;
		RuntimeException re;
		Error err;
		
		public void waitUntilSent(long maxWaitTime) throws NotConnectedException, ThrottledPacketLagException {
			long startTime = System.currentTimeMillis();
			long waitEndTime = startTime + maxWaitTime;
			synchronized(this) {
				while(!(sent || lostConn || re != null || err != null)) {
					try {
						long wait = waitEndTime - System.currentTimeMillis();
						if(wait > 0)
							wait(10*1000);
						if(wait <= 0) {
							throw new ThrottledPacketLagException();
						}
					} catch (InterruptedException e) {
						// Ignore
					}
				}
				if(lostConn) throw new NotConnectedException();
				if(re != null) throw re;
				if(err != null) throw err;
				long timeDiff = System.currentTimeMillis() - queuedTime;
				if(timeDiff > 30*1000)
					Logger.error(this, "Took "+timeDiff+" ms to send packet "+msg+" to "+pn);
				else
					Logger.minor(this, "Took "+timeDiff+" ms to send packet "+msg+" to "+pn);
				
			}
		}
	}

	public void sendPacket(Message msg, PeerNode pn, long maxWaitTime) throws NotConnectedException, ThrottledPacketLagException {
		ThrottledPacket p = queuePacket(msg, pn);
		p.waitUntilSent(maxWaitTime);
	}

	private ThrottledPacket queuePacket(Message msg, PeerNode pn) throws NotConnectedException {
		if(!pn.isConnected())
			throw new NotConnectedException();
		ThrottledPacket p = new ThrottledPacket(msg, pn);
		synchronized(queuedPackets) {
			queuedPackets.addLast(p);
			queuedPackets.notifyAll();
		}
		return p;
	}

	public void run() {
		while(true) {
			if(sendThrottledPacket()) {
				// Sent one
				// Sleep
				try {
					if(sleepTime > 0)
						Thread.sleep(sleepTime);
				} catch (InterruptedException e) {
					// Huh?
				}
			} else {
				// Didn't send one
				// Wait for one
				synchronized(queuedPackets) {
					while(queuedPackets.isEmpty())
						try {
							queuedPackets.wait(10*1000);
						} catch (InterruptedException e) {
							// Never mind
						}
				}
			}
			
		}
	}

	private boolean sendThrottledPacket() {
		while(true) {
			ThrottledPacket p;
			synchronized(queuedPackets) {
				if(queuedPackets.isEmpty()) return false;
				p = (ThrottledPacket) queuedPackets.removeFirst();
			}
			if(!p.pn.isConnected()) {
				synchronized(p) {
					p.lostConn = true;
					p.notifyAll();
				}
				continue;
			}
			try {
				p.pn.send(p.msg);
			} catch (NotConnectedException e) {
				synchronized(p) {
					p.lostConn = true;
					p.notifyAll();
				}
				continue;
			} catch (RuntimeException e) {
				synchronized(p) {
					p.re = e;
					p.notifyAll();
				}
				return true;
			} catch (Error e) {
				synchronized(p) {
					p.err = e;
					p.notifyAll();
				}
				return true;
			}
			synchronized(p) {
				p.sent = true;
				p.notifyAll();
			}
			return true;
		}
	}

}
