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
		}
		
		final Message msg;
		final PeerNode pn;
		boolean sent;
		
		public void waitUntilSent() {
			synchronized(this) {
				while(!sent) {
					try {
						wait(10*1000);
					} catch (InterruptedException e) {
						// Ignore
					}
				}
			}
		}
	}

	public void sendPacket(Message msg, PeerNode pn) throws NotConnectedException {
		ThrottledPacket p = queuePacket(msg, pn);
		p.waitUntilSent();
	}

	private ThrottledPacket queuePacket(Message msg, PeerNode pn) throws NotConnectedException {
		if(!pn.isConnected())
			throw new NotConnectedException();
		ThrottledPacket p = new ThrottledPacket(msg, pn);
		synchronized(queuedPackets) {
			queuedPackets.addLast(p);
			queuedPackets.notify();
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
			if(!p.pn.isConnected()) continue;
			try {
				p.pn.send(p.msg);
				synchronized(p) {
					p.sent = true;
					p.notifyAll();
				}
				return true;
			} catch (NotConnectedException e) {
				continue;
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t, t);
				continue;
			}
		}
	}

}
