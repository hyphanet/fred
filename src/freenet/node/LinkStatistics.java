package freenet.node;

import freenet.node.StatsChangeTracker;;

/**
 * @author quadrocube
 * Represents per-peer statistics concerning the transport layer stuff, mostly for debug purposes.
 */
public class LinkStatistics {
	
	public LinkStatistics(LinkStatistics o){
		this(o.getLastUpdated(), o.getDataSent(), o.getUsefullPaybackSent(), o.getAcksSent(), o.getPureAcksSent(), 
		     o.getPacketsRetransmitted(), o.getSeriousBackoffs(), 
	   	     o.getQueueBacklog(), o.getWindowSize(), o.getMaxUsedWindow(), o.getAverageRTT(), o.getRTO());
	}
	
	public LinkStatistics(long lastupdated, int datasent, int usefullpaybacksent, int ackssent, int pureackssent, 
						  int packetsretransmitted, int seriousbackoffs, 
						  double queuebacklog, double windowsize, double maxusedwindow, int averagertt, int rto) {
        Tracker = new StatsChangeTracker();
		lastUpdated = lastupdated;
		dataSent = datasent;
		usefullPaybackSent = usefullpaybacksent;
		acksSent = ackssent;
		pureAcksSent = pureackssent;
		packetsRetransmitted = packetsretransmitted;
		seriousBackoffs = seriousbackoffs;
		queueBacklog = queuebacklog;
		windowSize = windowsize;
		maxUsedWindow = maxusedwindow;
		averageRTT = averagertt;
		RTO = rto;
	}
	
	public LinkStatistics(StatsChangeTracker tracker, long lastupdated, int datasent, int usefullpaybacksent, int ackssent, 
			  			  int pureackssent, int packetsretransmitted, int seriousbackoffs, 
			  			  double queuebacklog, double windowsize, double maxusedwindow, int averagertt, int rto) {
		this(lastupdated, datasent, usefullpaybacksent, ackssent, pureackssent, packetsretransmitted, seriousbackoffs, queuebacklog,
				windowsize, maxusedwindow, averagertt, rto);
		Tracker = tracker;
		}

	public LinkStatistics(){
		this(System.currentTimeMillis(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
	}
	
	public LinkStatistics(StatsChangeTracker tracker){
		this(tracker, System.currentTimeMillis(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
	}
	
    public StatsChangeTracker Tracker;

	protected long lastUpdated;
	
	/** Inside part - tracked directly in PeerNode */
	
	protected int dataSent;
	/** This excludes all the additional headers, acks, etc. Counts pure transmitted payback. In bytes */
	protected int usefullPaybackSent;
	/**  */
	protected int acksSent;
	/** Count of packets that *only* acknowledge data */
	protected int pureAcksSent;
	/** How many times did we retransmit a packet due to fast retransmit (does not include serious backoffs after timeout) */
	protected int packetsRetransmitted;
	/** How many times did we back off. Note that it will always be zero upon reset, e.g. due to backoff */
	protected int seriousBackoffs;
	/** Amount of data already sent over the link. In bytes */
	
	/** Outside part - gathered from outermost controllers */
	
	/** As denoted by TCP Veno */
	protected double queueBacklog;
	/** Congestion window size */
	protected double windowSize;
	/** Maximum ever utilized cwnd size (see congestion control - PacketThrottle, etc). 
	 ** Same units as PacketThrottle._windowSize */
	protected double maxUsedWindow;
	/** Average seen Round Trip Time. See PacketThrottle for more */
	protected int averageRTT;
	/** See PacketThrottle */
	protected int RTO;
	
	
	public void reset(){
		lastUpdated = System.currentTimeMillis();
		dataSent = usefullPaybackSent = acksSent = pureAcksSent = packetsRetransmitted = 
				seriousBackoffs = averageRTT = RTO = 0;
        queueBacklog = windowSize = maxUsedWindow = 0.0;
	}
	
    public long getLastUpdated(){
        return lastUpdated;
    }
	public int getDataSent(){
        return dataSent;
    }
	public int getUsefullPaybackSent(){
        return usefullPaybackSent;
    }
	public int getAcksSent(){
        return acksSent;
    }
	public int getPureAcksSent(){
        return pureAcksSent;
    }
	public int getPacketsRetransmitted(){
        return packetsRetransmitted;
    }
	public int getSeriousBackoffs(){
        return seriousBackoffs;
    }
	public double getQueueBacklog(){
        return queueBacklog;
    }
	public double getWindowSize(){
        return windowSize;
    }
	public double getMaxUsedWindow(){
        return maxUsedWindow;
    }
	public int getAverageRTT(){
        return averageRTT;
    }
	public int getRTO(){
        return RTO;
    }
    
	public void setDataSent(int newval){
        lastUpdated = System.currentTimeMillis();
        int previousval = dataSent;
        dataSent = newval;
        Tracker.dataSentChanged(previousval, newval);
    }
	public void setUsefullPaybackSent(int newval){
        lastUpdated = System.currentTimeMillis();
        int previousval = usefullPaybackSent;
        usefullPaybackSent = newval;
        Tracker.usefullPaybackSentChanged(previousval, newval);
    }
	public void setAcksSent(int newval){
        lastUpdated = System.currentTimeMillis();
        int previousval = acksSent;
        acksSent = newval;
        Tracker.acksSentChanged(previousval, newval);
    }
	public void setPureAcksSent(int newval){
        lastUpdated = System.currentTimeMillis();
        int previousval = pureAcksSent;
        pureAcksSent = newval;
        Tracker.pureAcksSentChanged(previousval, newval);
    }
	public void setPacketsRetransmitted(int newval){
        lastUpdated = System.currentTimeMillis();
        int previousval = packetsRetransmitted;
        packetsRetransmitted = newval;
        Tracker.packetsRetransmittedChanged(previousval, newval);
    }
	public void setSeriousBackoffs(int newval){
        lastUpdated = System.currentTimeMillis();
        int previousval = seriousBackoffs;
        seriousBackoffs = newval;
        Tracker.seriousBackoffsChanged(previousval, newval);
    }
	public void setQueueBacklog(double newval){
        lastUpdated = System.currentTimeMillis();
        double previousval = queueBacklog;
        queueBacklog = newval;
        Tracker.queueBacklogChanged(previousval, newval);
    }
	public void setWindowSize(double newval){
        lastUpdated = System.currentTimeMillis();
        double previousval = windowSize;
        windowSize = newval;
        Tracker.windowSizeChanged(previousval, newval);
    }
	public void setMaxUsedWindow(double newval){
        lastUpdated = System.currentTimeMillis();
        double previousval = maxUsedWindow;
        maxUsedWindow = newval;
        Tracker.maxUsedWindowChanged(previousval, newval);
    }
	public void setAverageRTT(int newval){
        lastUpdated = System.currentTimeMillis();
        int previousval = averageRTT;
        averageRTT = newval;
        Tracker.averageRTTChanged(previousval, newval);
    }
	public void setRTO(int newval){
        lastUpdated = System.currentTimeMillis();
        int previousval = RTO;
        RTO = newval;
        Tracker.RTOChanged(previousval, newval);
    }

}
