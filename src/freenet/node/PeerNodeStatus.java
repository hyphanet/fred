/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;
 
import java.util.Map;

import freenet.clients.http.DarknetConnectionsToadlet;
import freenet.io.comm.Peer;
import freenet.io.xfer.PacketThrottle;
import freenet.node.NodeStats.PeerLoadStats;
import freenet.node.PeerNode.IncomingLoadSummaryStats;

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
	private final double[] peersLocation;

	private final String version;

	private final int simpleVersion;

	private final int routingBackoffLengthRT;
	private final int routingBackoffLengthBulk;

	private final long routingBackedOffUntilRT;
	private final long routingBackedOffUntilBulk;

	private final boolean connected;

	private final boolean routable;

	private final boolean isFetchingARK;

	private final boolean isOpennet;

	private final double averagePingTime;
	private final double averagePingTimeCorrected;

	private final boolean publicInvalidVersion;

	private final boolean publicReverseInvalidVersion;
	
	private final double backedOffPercent;
	private final double backedOffPercentRT;
	private final double backedOffPercentBulk;

	private String lastBackoffReasonRT;
	private String lastBackoffReasonBulk;

	private long timeLastRoutable;

	private long timeLastConnectionCompleted;

	private long peerAddedTime;

	private Map<String,Long> localMessagesReceived;

	private Map<String,Long> localMessagesSent;
	
	private final int hashCode;
	
	private final double pReject;

	private long totalBytesIn;
	
	private long totalBytesOut;

	private long totalBytesInSinceStartup;
		
	private long totalBytesOutSinceStartup;
	
	private double percentTimeRoutableConnection;
	
	private PacketThrottle throttle;
	
	private long clockDelta;
	
	private final boolean recordStatus;
	
	private final boolean isSeedServer;
	
	private final boolean isSeedClient;
	
	private final boolean isSearchable;
	
	private final long resendBytesSent;
	
	private final int reportedUptimePercentage;
	
	private final double selectionRate;

	private final long messageQueueLengthBytes;
	
	private final long messageQueueLengthTime;
	// int's because that's what they are transferred as
	
	public final IncomingLoadSummaryStats incomingLoadStatsRealTime;

	public final IncomingLoadSummaryStats incomingLoadStatsBulk;

	PeerNodeStatus(PeerNode peerNode, boolean noHeavy) {
		Peer p = peerNode.getPeer();
		if(p == null) {
			peerAddress = null;
			peerPort = -1;
		} else {
			peerAddress = p.getFreenetAddress().toString();
			peerPort = p.getPort();
		}
		this.selectionRate = peerNode.selectionRate();
		this.statusValue = peerNode.getPeerNodeStatus();
		this.statusName = peerNode.getPeerNodeStatusString();
		this.statusCSSName = peerNode.getPeerNodeStatusCSSClassName();
		this.location = peerNode.getLocation();
		this.peersLocation = peerNode.getPeersLocation();
		this.version = peerNode.getVersion();
		this.simpleVersion = peerNode.getSimpleVersion();
		this.routingBackoffLengthRT = peerNode.getRoutingBackoffLength(true);
		this.routingBackoffLengthBulk = peerNode.getRoutingBackoffLength(false);
		this.routingBackedOffUntilRT = peerNode.getRoutingBackedOffUntil(true);
		this.routingBackedOffUntilBulk = peerNode.getRoutingBackedOffUntil(false);
		this.connected = peerNode.isConnected();
		this.routable = peerNode.isRoutable();
		this.isFetchingARK = peerNode.isFetchingARK();
		this.isOpennet = peerNode.isOpennet();
		this.averagePingTime = peerNode.averagePingTime();
		this.averagePingTimeCorrected = peerNode.averagePingTimeCorrected();
		this.publicInvalidVersion = peerNode.publicInvalidVersion();
		this.publicReverseInvalidVersion = peerNode.publicReverseInvalidVersion();
		this.backedOffPercent = peerNode.backedOffPercent.currentValue();
		this.backedOffPercentRT = peerNode.backedOffPercentRT.currentValue();
		this.backedOffPercentBulk = peerNode.backedOffPercentBulk.currentValue();
		this.lastBackoffReasonRT = peerNode.getLastBackoffReason(true);
		this.lastBackoffReasonBulk = peerNode.getLastBackoffReason(false);
		this.timeLastRoutable = peerNode.timeLastRoutable();
		this.timeLastConnectionCompleted = peerNode.timeLastConnectionCompleted();
		this.peerAddedTime = peerNode.getPeerAddedTime();
		if(!noHeavy) {
			this.localMessagesReceived = peerNode.getLocalNodeReceivedMessagesFromStatistic();
			this.localMessagesSent = peerNode.getLocalNodeSentMessagesToStatistic();
		} else {
			this.localMessagesReceived = null;
			this.localMessagesSent = null;
		}
		this.hashCode = peerNode.hashCode;
		this.pReject = peerNode.getPRejected();
		this.totalBytesIn = peerNode.getTotalInputBytes();
		this.totalBytesOut = peerNode.getTotalOutputBytes();
		this.totalBytesInSinceStartup = peerNode.getTotalInputSinceStartup();
		this.totalBytesOutSinceStartup = peerNode.getTotalOutputSinceStartup();
		this.percentTimeRoutableConnection = peerNode.getPercentTimeRoutableConnection();
		this.throttle = peerNode.getThrottle();
		this.clockDelta = peerNode.getClockDelta();
		this.recordStatus = peerNode.recordStatus();
		this.isSeedClient = peerNode instanceof SeedClientPeerNode;
		this.isSeedServer = peerNode instanceof SeedServerPeerNode;
		this.isSearchable = peerNode.isRealConnection();
		this.resendBytesSent = peerNode.getResendBytesSent();
		this.reportedUptimePercentage = peerNode.getUptime();
		messageQueueLengthBytes = peerNode.getMessageQueueLengthBytes();
		messageQueueLengthTime = peerNode.getProbableSendQueueTime();
		incomingLoadStatsRealTime = peerNode.getIncomingLoadStats(true);
		incomingLoadStatsBulk = peerNode.getIncomingLoadStats(false);
	}
	
	public long getMessageQueueLengthBytes() {
		return messageQueueLengthBytes;
	}
	
	public long getMessageQueueLengthTime() {
		return messageQueueLengthTime;
	}

	/**
	 * @return the localMessagesReceived
	 */
	public Map<String, Long> getLocalMessagesReceived() {
		return localMessagesReceived;
	}

	/**
	 * @return the localMessagesSent
	 */
	public Map<String, Long> getLocalMessagesSent() {
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
	public double getBackedOffPercent(boolean realTime) {
		return realTime ? backedOffPercentRT : backedOffPercentBulk;
	}

	/**
	 * @return the lastBackoffReason
	 */
	public String getLastBackoffReason(boolean realTime) {
		return realTime ? lastBackoffReasonRT : lastBackoffReasonBulk;
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
	 * @return The ping time for purposes of retransmissions.
	 */
	public double getAveragePingTimeCorrected() {
		return averagePingTimeCorrected;
	}

	/**
	 * @return the getRoutingBackedOffUntil
	 */
	public long getRoutingBackedOffUntil(boolean realTime) {
		return realTime ? routingBackedOffUntilRT : routingBackedOffUntilBulk;
	}

	/**
	 * @return the location
	 */
	public double getLocation() {
		return location;
	}

	public double[] getPeersLocation() {
		return peersLocation;
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
	public int getRoutingBackoffLength(boolean realTime) {
		return realTime ? routingBackoffLengthRT : routingBackoffLengthBulk;
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
	 * @return the isOpennet
	 */
	public boolean isOpennet() {
		return isOpennet;
	}

	/**
	 * @return the simpleVersion
	 */
	public int getSimpleVersion() {
		return simpleVersion;
	}

	@Override
	public String toString() {
		return statusName + ' ' + peerAddress + ':' + peerPort + ' ' + location + ' ' + version + " RT backoff: " + routingBackoffLengthRT + " (" + (Math.max(routingBackedOffUntilRT - System.currentTimeMillis(), 0)) + " ) bulk backoff: " + routingBackoffLengthBulk + " (" + (Math.max(routingBackedOffUntilBulk - System.currentTimeMillis(), 0)) + ')';
	}

	@Override
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

	public long getTotalInputSinceStartup() {
		return totalBytesInSinceStartup;
	}
	
	public long getTotalOutputSinceStartup() {
		return totalBytesOutSinceStartup;
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
	
	public boolean recordStatus() {
		return recordStatus;
	}

	public boolean isSeedServer() {
		return isSeedServer;
	}

	public boolean isSeedClient() {
		return isSeedClient;
	}
	
	public boolean isSearchable() {
		return isSearchable;
	}
	
	public long getResendBytesSent() {
		return resendBytesSent;
	}
	
	public int getReportedUptimePercentage() {
		return reportedUptimePercentage;
	}

	public double getSelectionRate() {
		return selectionRate;
	}
}
