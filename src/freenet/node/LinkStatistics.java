package freenet.node;

/**
 * @author quadrocube
 * Represents per-peer statistics concerning the transport layer stuff, mostly for debug purposes.
 */
public class LinkStatistics {
	
	public LinkStatistics(LinkStatistics o){
		this(o.getLastUpdated(), o.getLastReset(), o.getDataSent(), o.getMessagePayloadSent(), o.getDataAcked(),
		     o.getDataRetransmitted(), o.getRetransmitCount(), o.getSeriousBackoffs(), 
	   	     o.getQueueBacklog(), o.getWindowSize(), o.getMaxUsedWindow(), o.getAverageRTT(), o.getRTO());
	}
	
	public LinkStatistics(long lastupdated, long lastreset, long datasent, long usefullpaybacksent, long dataacked,
						  long dataretransmitted, long retransmitcount, long seriousbackoffs, 
						  double queuebacklog, double windowsize, double maxusedwindow, double averagertt, double rto) {
        tracker = new StatsChangeTracker();
		lastUpdated = lastupdated;
		dataSent = datasent;
		messagePayloadSent = usefullpaybacksent;
		dataAcked = dataacked;
		dataRetransmitted = dataretransmitted;
		retransmitCount = retransmitcount;
		seriousBackoffs = seriousbackoffs;
		queueBacklog = queuebacklog;
		windowSize = windowsize;
		maxUsedWindow = maxusedwindow;
		averageRTT = averagertt;
		RTO = rto;
	}
	
	public LinkStatistics(StatsChangeTracker fromTracker, long lastupdated, long lastreset, long datasent, long usefullpaybacksent, 
					      long dataacked, long dataretransmitted, long retransmitcount, long seriousbackoffs, 
			  			  double queuebacklog, double windowsize, double maxusedwindow, long averagertt, long rto) {
		this(lastupdated, lastreset, datasent, usefullpaybacksent, dataacked, dataretransmitted, retransmitcount, seriousbackoffs, 
				queuebacklog, windowsize, maxusedwindow, averagertt, rto);
		tracker = fromTracker;
		}

	public LinkStatistics(){
		this(System.currentTimeMillis(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
	}
	
	public LinkStatistics(StatsChangeTracker fromTracker){
		this(fromTracker, System.currentTimeMillis(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
	}
	
	/** Contains a bunch on-modification callbacks, if you want to pipe stats over somewhere - overload them. */
	public static class StatsChangeTracker {
		public void dataSentChanged(long previousval, long newval, long time) {
	    }
		public void messagePayloadSentChanged(long previousval, long newval, long time) {
	    }
		public void acksSentChanged(long previousval, long newval, long time) {
	    }
		public void pureAcksSentChanged(long previousval, long newval, long time) {
	    }
		public void dataRetransmittedChanged(long previousval, long newval, long time) {
	    }
		public void seriousBackoffsChanged(long previousval, long newval, long time) {
	    }
		public void queueBacklogChanged(double previousval, double newval, long time) {
	    }
		public void windowSizeChanged(double previousval, double newval, long time) {
	    }
		public void maxUsedWindowChanged(double previousval, double newval, long time) {
	    }
		public void averageRTTChanged(double previousval, double newval, long time) {
	    }
		public void RTOChanged(double previousval, double newval, long time) {
	    }
	}
	
    public StatsChangeTracker tracker;
    
    /* Resets statistics and assigns new tracker */
    public void attachListener(StatsChangeTracker t) {
    	synchronized (this) {
    		reset();
    		seriousBackoffs = 0;
    		tracker = t;
    	}
    }

	protected long lastUpdated;
	/* The last time reset() was called */
	protected long lastReset;
	
	/** Inside part - tracked directly in PeerNode */
	
	/* TODO: Have a look at RunningAverage 's, perhaps utilize some of those? e.g. for bandwidth */
	
	protected long dataSent;
	/** This excludes all the additional headers, acks, etc. Counts pure transmitted payback. In bytes */
	protected long messagePayloadSent;
	/** Amount of data (in bytes) that we received an ack for. 
	 *  Includes only acknowledged data that was transmitted after the last reset */
	protected long dataAcked;
	/** How many times did we retransmit a packet */
	protected long retransmitCount;
	/** How much data did we retransmit */
	protected long dataRetransmitted;
	/** How many times did we back off. Note that it will always be zero upon reset, e.g. due to backoff */
	/*FIXME: Rename to something more suitable and less conflicting */
	protected long seriousBackoffs;
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
	protected double averageRTT;
	/** See PacketThrottle */
	protected double RTO;
	
	
	public void reset(){
		lastUpdated = System.currentTimeMillis();
        lastReset = lastUpdated;
		dataSent = messagePayloadSent = dataAcked = dataRetransmitted = 0;
        queueBacklog = windowSize = maxUsedWindow = RTO = averageRTT = 0.0;
	}
	
    public long getLastUpdated(){
        return lastUpdated;
    }
    public long getLastReset(){
        return lastReset;
    }
	public long getDataSent(){
        return dataSent;
    }
	public long getMessagePayloadSent(){
        return messagePayloadSent;
    }
	public long getDataAcked(){
        return dataAcked;
    }
	public long getDataRetransmitted(){
        return dataRetransmitted;
    }
	public long getRetransmitCount(){
		return retransmitCount;
	}
	public long getSeriousBackoffs(){
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
	public double getAverageRTT(){
        return averageRTT;
    }
	public double getRTO(){
        return RTO;
    }
    
	public void onDataSend(long amount){
		long previousval;
        synchronized (this) {
            lastUpdated = System.currentTimeMillis();
            previousval = dataSent;
            dataSent += amount;
        }
        if (tracker != null)
        tracker.dataSentChanged(previousval, dataSent, lastUpdated);
    }
	public void onMessagePayloadSent(long amount){
        long previousval;
        synchronized (this) {
            lastUpdated = System.currentTimeMillis();
            previousval = messagePayloadSent;
            messagePayloadSent += amount;
        }
        if (tracker != null)
        	tracker.messagePayloadSentChanged(previousval, messagePayloadSent, lastUpdated);
    }
	/**
	 * @param amount - amount of data acknowledged
	 * @param whenSent - time when the acknowledged data was last sent or retransmitted  
	 */
	public void onNewDataAck(long amount, long whenSent){
        long previousval;
        synchronized (this) {
            previousval = dataAcked;
            if (whenSent > lastReset) {
			    lastUpdated = System.currentTimeMillis();
	            dataAcked += amount;
            }
        }
        if (whenSent > lastReset && tracker != null)
        	tracker.acksSentChanged(previousval, dataAcked, lastUpdated); 
    }
	public void onDataRetransmit(long amount){
        long previousval;
        synchronized (this) {
            lastUpdated = System.currentTimeMillis();
            ++retransmitCount;
            previousval = dataRetransmitted;
            dataRetransmitted += amount;
        }
        if (tracker != null)
        	tracker.dataRetransmittedChanged(previousval, dataRetransmitted, lastUpdated);
    }
	public void onSeriousBackoff(long amount){
        long previousval;
        synchronized (this) {
            lastUpdated = System.currentTimeMillis();
            previousval = seriousBackoffs;
            seriousBackoffs += amount;
        }
        if (tracker != null)
        	tracker.seriousBackoffsChanged(previousval, seriousBackoffs, lastUpdated);
    }
	public void onQueueBacklogChange(double newval){ // For later use
        double previousval;
        synchronized (this) {
            lastUpdated = System.currentTimeMillis();
            previousval = queueBacklog;
            queueBacklog += newval;
        }
        if (tracker != null)
        	tracker.queueBacklogChanged(previousval, queueBacklog, lastUpdated);
    }
	public void onWindowSizeChange(double newval){
        double previousval;
        synchronized (this) {
            lastUpdated = System.currentTimeMillis();
            previousval = windowSize;
            windowSize = newval;
        }
        if (tracker != null)
        	tracker.windowSizeChanged(previousval, windowSize, lastUpdated);
    }
	public void onMaxUsedWindowChange(double newval){ // For later use
        double previousval;
        synchronized (this) {
            lastUpdated = System.currentTimeMillis();
            previousval = maxUsedWindow;
            maxUsedWindow = newval;
        }
        tracker.maxUsedWindowChanged(previousval, maxUsedWindow, lastUpdated);
    }
	public void onAverageRTTChange(double newval){
        double previousval;
        synchronized (this) {
            lastUpdated = System.currentTimeMillis();
            previousval = averageRTT;
            averageRTT = newval;
        }
        if (tracker != null)
        	tracker.averageRTTChanged(previousval, averageRTT, lastUpdated);
    }
	public void onRTOChange(double newval){
        double previousval;
        synchronized (this) {
            lastUpdated = System.currentTimeMillis();
            previousval = RTO;
            RTO = newval;
        }
        if (tracker != null)
        	tracker.RTOChanged(previousval, RTO, lastUpdated);
    }

}
