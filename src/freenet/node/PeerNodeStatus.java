/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.Hashtable;
import java.util.Map;

import freenet.clients.http.DarknetConnectionsToadlet;
import freenet.io.comm.Peer;
import freenet.io.xfer.PacketThrottle;

/**
 * Contains various status information for a {@link PeerNode}. Used e.g. in
 * {@link DarknetConnectionsToadlet} to reduce race-conditions while creating
 * the page.
 * 
 * @author David 'Bombe' Roden &lt;bombe@freenetproject.org&gt;
 * @version $Id$
 */
public class PeerNodeStatus {

	private final String peerAddress;

	private final int peerPort;

	private final int statusValue;

	private final String statusName;

	private final String statusCSSName;

	private final double location;

	private final String version;

	private final String simpleVersion;

	private final int routingBackoffLength;

	private final long routingBackedOffUntil;

	private final boolean connected;

	private final boolean routable;

	private final boolean isFetchingARK;

	private final double averagePingTime;

	private final boolean publicInvalidVersion;

	private final boolean publicReverseInvalidVersion;

	private final double backedOffPercent;

	private String lastBackoffReason;

	private long timeLastRoutable;

	private long timeLastConnectionCompleted;

	private long peerAddedTime;

	private Map localMessagesReceived;

	private Map localMessagesSent;
	
	private final int hashCode;
	
	private final double pReject;

	private long totalBytesIn;
	
	private long totalBytesOut;
	
	private double percentTimeRoutableConnection;
	
	private PacketThrottle throttle;
	
	private long clockDelta;

	PeerNodeStatus(PeerNode peerNode) {
		Peer p = peerNode.getPeer();
		if(p == null) {
			peerAddress = null;
			peerPort = -1;
		} else {
			peerAddress = p.getFreenetAddress().toString();
			peerPort = p.getPort();
		}
		this.statusValue = peerNode.getPeerNodeStatus();
		this.statusName = peerNode.getPeerNodeStatusString();
		this.statusCSSName = peerNode.getPeerNodeStatusCSSClassName();
		this.location = peerNode.getLocation();
		this.version = peerNode.getVersion();
		this.simpleVersion = peerNode.getSimpleVersion();
		this.routingBackoffLength = peerNode.getRoutingBackoffLength();
		this.routingBackedOffUntil = peerNode.getRoutingBackedOffUntil();
		this.connected = peerNode.isConnected();
		this.routable = peerNode.isRoutable();
		this.isFetchingARK = peerNode.isFetchingARK();
		this.averagePingTime = peerNode.averagePingTime();
		this.publicInvalidVersion = peerNode.publicInvalidVersion();
		this.publicReverseInvalidVersion = peerNode.publicReverseInvalidVersion();
		this.backedOffPercent = peerNode.backedOffPercent.currentValue();
		this.lastBackoffReason = peerNode.getLastBackoffReason();
		this.timeLastRoutable = peerNode.timeLastRoutable();
		this.timeLastConnectionCompleted = peerNode.timeLastConnectionCompleted();
		this.peerAddedTime = peerNode.getPeerAddedTime();
		this.localMessagesReceived = new Hashtable(peerNode.getLocalNodeReceivedMessagesFromStatistic());
		this.localMessagesSent = new Hashtable(peerNode.getLocalNodeSentMessagesToStatistic());
		this.hashCode = peerNode.hashCode;
		this.pReject = peerNode.getPRejected();
		this.totalBytesIn = peerNode.getTotalInputBytes();
		this.totalBytesOut = peerNode.getTotalOutputBytes();
		this.percentTimeRoutableConnection = peerNode.getPercentTimeRoutableConnection();
		this.throttle = peerNode.getThrottle();
		this.clockDelta = peerNode.getClockDelta();
	}

	/**
	 * @return the localMessagesReceived
	 */
	public Map getLocalMessagesReceived() {
		return localMessagesReceived;
	}

	/**
	 * @return the localMessagesSent
	 */
	public Map getLocalMessagesSent() {
		return localMessagesSent;
	}

	/**
	 * @return the peerAddedTime
	 */
	public long getPeerAddedTime() {
		return peerAddedTime;
	}

	/**
	 * Counts the peers in <code>peerNodes</code> that have the specified
	 * status.
	 * @param peerNodeStatuses The peer nodes' statuses
	 * @param status The status to count
	 * @return The number of peers that have the specified status.
	 */
	public static int getPeerStatusCount(PeerNodeStatus[] peerNodeStatuses, int status) {
		int count = 0;
		for (int peerIndex = 0, peerCount = peerNodeStatuses.length; peerIndex < peerCount; peerIndex++) {
			if (peerNodeStatuses[peerIndex].getStatusValue() == status) {
				count++;
			}
		}
		return count;
	}

	/**
	 * @return the timeLastConnectionCompleted
	 */
	public long getTimeLastConnectionCompleted() {
		return timeLastConnectionCompleted;
	}

	/**
	 * @return the backedOffPercent
	 */
	public double getBackedOffPercent() {
		return backedOffPercent;
	}

	/**
	 * @return the lastBackoffReason
	 */
	public String getLastBackoffReason() {
		return lastBackoffReason;
	}

	/**
	 * @return the timeLastRoutable
	 */
	public long getTimeLastRoutable() {
		return timeLastRoutable;
	}

	/**
	 * @return the publicInvalidVersion
	 */
	public boolean isPublicInvalidVersion() {
		return publicInvalidVersion;
	}

	/**
	 * @return the publicReverseInvalidVersion
	 */
	public boolean isPublicReverseInvalidVersion() {
		return publicReverseInvalidVersion;
	}

	/**
	 * @return the averagePingTime
	 */
	public double getAveragePingTime() {
		return averagePingTime;
	}

	/**
	 * @return the getRoutingBackedOffUntil
	 */
	public long getRoutingBackedOffUntil() {
		return routingBackedOffUntil;
	}

	/**
	 * @return the location
	 */
	public double getLocation() {
		return location;
	}

	/**
	 * @return the peerAddress
	 */
	public String getPeerAddress() {
		return peerAddress;
	}

	/**
	 * @return the peerPort
	 */
	public int getPeerPort() {
		return peerPort;
	}

	/**
	 * @return the routingBackoffLength
	 */
	public int getRoutingBackoffLength() {
		return routingBackoffLength;
	}

	/**
	 * @return the statusCSSName
	 */
	public String getStatusCSSName() {
		return statusCSSName;
	}

	/**
	 * @return the statusName
	 */
	public String getStatusName() {
		return statusName;
	}

	/**
	 * @return the statusValue
	 */
	public int getStatusValue() {
		return statusValue;
	}

	/**
	 * @return the version
	 */
	public String getVersion() {
		return version;
	}

	/**
	 * @return the connected
	 */
	public boolean isConnected() {
		return connected;
	}

	/**
	 * @return the routable
	 */
	public boolean isRoutable() {
		return routable;
	}

	/**
	 * @return the isFetchingARK
	 */
	public boolean isFetchingARK() {
		return isFetchingARK;
	}

	/**
	 * @return the simpleVersion
	 */
	public String getSimpleVersion() {
		return simpleVersion;
	}

	public String toString() {
		return statusName + ' ' + peerAddress + ':' + peerPort + ' ' + location + ' ' + version + " backoff: " + routingBackoffLength + " (" + (Math.max(routingBackedOffUntil - System.currentTimeMillis(), 0)) + ')';
	}

	public int hashCode() {
		return hashCode;
	}

	public double getPReject() {
		return pReject;
	}

	public long getTotalInputBytes() {
		return totalBytesIn;
	}
	
	public long getTotalOutputBytes() {
		return totalBytesOut;
	}
	
	public double getPercentTimeRoutableConnection() {
		return percentTimeRoutableConnection;
	}

	public PacketThrottle getThrottle() {
		return throttle;
	}

	public long getClockDelta() {
		return clockDelta;
	}
}
