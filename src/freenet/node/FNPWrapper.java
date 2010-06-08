package freenet.node;

import java.util.ArrayList;
import java.util.Vector;

import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.PacketSocketHandler;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.WouldBlockException;

public class FNPWrapper {

	private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(Logger.MINOR, this);
			}
		});
	}

	private PeerNode pn;

	public FNPWrapper(PeerNode pn) {
		this.pn = pn;
	}

	/**
	 * Maybe send something. A SINGLE PACKET. Don't send everything at once, for two reasons:
	 * <ol>
	 * <li>It is possible for a node to have a very long backlog.</li>
	 * <li>Sometimes sending a packet can take a long time.</li>
	 * <li>In the near future PacketSender will be responsible for output bandwidth throttling. So it makes sense to
	 * send a single packet and round-robin.</li>
	 * </ol>
	 */
	public boolean maybeSendPacket(long now, Vector<ResendPacketItem> rpiTemp, int[] rpiIntTemp)
	                throws BlockedTooLongException {
		// If there are any urgent notifications, we must send a packet.
		if(logMINOR) Logger.minor(this, "maybeSendPacket: " + this);
		boolean mustSend = false;
		boolean mustSendPacket = false;
		if(mustSendNotificationsNow(now)) {
			if(logMINOR) Logger.minor(this, "Sending notification");
			mustSend = true;
			mustSendPacket = true;
		}
		// Any packets to resend? If so, resend ONE packet and then return.
		for (int j = 0; j < 2; j++) {
			SessionKey kt;
			if(j == 0) {
				kt = pn.getCurrentKeyTracker();
			} else /* if(j == 1) */ {
				kt = pn.getPreviousKeyTracker();
			}
			if(kt == null) continue;
			int[] tmp = kt.packets.grabResendPackets(rpiTemp, rpiIntTemp);
			if(tmp == null) continue;
			rpiIntTemp = tmp;
			for (ResendPacketItem item : rpiTemp) {
				if(item == null) continue;
				try {
					if(logMINOR) {
						Logger.minor(this, "Resending " + item.packetNumber + " to " + item.kt);
					}
					pn.getOutgoingMangler().resend(item, kt);
					return true;
				} catch (KeyChangedException e) {
					Logger.error(this, "Caught " + e + " resending packets to " + kt);
					pn.requeueResendItems(rpiTemp);
					return false;
				} catch (NotConnectedException e) {
					Logger.normal(this, "Caught " + e + " resending packets to " + kt);
					pn.requeueResendItems(rpiTemp);
					return false;
				} catch (PacketSequenceException e) {
					Logger.error(this, "Caught " + e + " - disconnecting", e);
					// PSE is fairly drastic, something is broken between us, but maybe we can
					// resync
					pn.forceDisconnect(false);
					return false;
				} catch (WouldBlockException e) {
					Logger.error(this, "Impossible: " + e, e);
					return false;
				}
			}

		}
		int minSize = pn.getOutgoingMangler().fullHeadersLengthOneMessage(); // includes UDP headers
		int maxSize = ((PacketSocketHandler) pn.getSocketHandler()).getPacketSendThreshold();

		// If it's a keepalive, we must add an FNPVoid to ensure it has a packet number.
		boolean keepalive = false;

		long lastSent = pn.lastSentPacketTime();
		if(now - lastSent > Node.KEEPALIVE_INTERVAL) {
			if(logMINOR) Logger.minor(this, "Sending keepalive");
			if(now - lastSent > Node.KEEPALIVE_INTERVAL * 10 && lastSent > -1) {
				Logger.error(this, "Long gap between sending packets: " + (now - lastSent) + " for "
				                + pn);
			}
			keepalive = true;
			mustSend = true;
			mustSendPacket = true;
		}

		ArrayList<MessageItem> messages = new ArrayList<MessageItem>(10);

		PeerMessageQueue messageQueue = pn.getMessageQueue();
		synchronized(messageQueue) {

			// Any urgent messages to send now?

			if(!mustSend) {
				if(messageQueue.mustSendNow(now)) mustSend = true;
			}

			if(!mustSend) {
				// What about total length?
				if(messageQueue.mustSendSize(minSize, maxSize)) mustSend = true;
			}

			if(mustSend) {
				int size = minSize;
				size = messageQueue.addUrgentMessages(size, now, minSize, maxSize, messages);

				// Now the not-so-urgent messages.
				if(size >= 0) {
					size = messageQueue.addNonUrgentMessages(size, now, minSize, maxSize, messages);
				}
			}

		}

		if(messages.isEmpty() && keepalive) {
			// Force packet to have a sequence number.
			Message m = DMT.createFNPVoid();
			pn.addToLocalNodeSentMessagesToStatistic(m);
			messages.add(new MessageItem(m, null, null, pn));
		}

		if(!messages.isEmpty()) {
			// Send packets, right now, blocking, including any active notifications
			// Note that processOutgoingOrRequeue will drop messages from the end
			// if necessary to fit the messages into a single packet.
			if(!pn.getOutgoingMangler().processOutgoingOrRequeue(
			                messages.toArray(new MessageItem[messages.size()]), pn, false, true)) {
				if(mustSendPacket) {
					if(!pn.sendAnyUrgentNotifications(false)) pn.sendAnyUrgentNotifications(true);
				}
			}
			return true;
		} else {
			if(mustSend) {
				if(pn.sendAnyUrgentNotifications(false)) return true;
				// Can happen occasionally as a race condition...
				Logger.normal(this, "No notifications sent despite no messages and mustSend=true for "
				                + pn);
			}
		}

		return false;
	}

	private boolean mustSendNotificationsNow(long now) {
		synchronized(pn) {
			SessionKey kt = pn.getCurrentKeyTracker();
			if(kt != null) {
				if(kt.packets.getNextUrgentTime() < now) return true;
			}
			kt = pn.getPreviousKeyTracker();
			if(kt != null) if(kt.packets.getNextUrgentTime() < now) return true;
			return false;
		}
	}

	public void handleReceivedPacket(byte[] buf, int offset, int length, long now) {
		pn.crypto.packetMangler.process(buf, offset, length, pn.getPeer(), now);
	}

}
