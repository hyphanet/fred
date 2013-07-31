package freenet.node;

/**
 * @author quadrocube
 * Represents per-peer statistics concerning the transport layer stuff, mostly for debug purposes.
 */
public class LinkStatistics {
	
	public LinkStatistics(LinkStatistics o){
		this(o.getLastUpdated(), o.getLastReset(), o.getDataSent(), o.getUsefullPaybackSent(), o.getDataAcked(),
		     o.getDataRetransmitted(), o.getRetransmitCount(), o.getSeriousBackoffs(), 
	   	     o.getQueueBacklog(), o.getWindowSize(), o.getMaxUsedWindow(), o.getAverageRTT(), o.getRTO());
	}
	
	public LinkStatistics(long lastupdated, long lastreset, long datasent, long usefullpaybacksent, long dataacked,
						  long dataretransmitted, long retransmitcount, long seriousbackoffs, 
						  double queuebacklog, double windowsize, double maxusedwindow, double averagertt, double rto) {
        Tracker = new StatsChangeTracker();
		lastUpdated = lastupdated;
		dataSent = datasent;
		usefullPaybackSent = usefullpaybacksent;
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
	
	public LinkStatistics(StatsChangeTracker tracker, long lastupdated, long lastreset, long datasent, long usefullpaybacksent, 
					      long dataacked, long dataretransmitted, long retransmitcount, long seriousbackoffs, 
			  			  double queuebacklog, double windowsize, double maxusedwindow, long averagertt, long rto) {
		this(lastupdated, lastreset, datasent, usefullpaybacksent, dataacked, dataretransmitted, retransmitcount, seriousbackoffs, 
				queuebacklog, windowsize, maxusedwindow, averagertt, rto);
		Tracker = tracker;
		}

	public LinkStatistics(){
		this(System.currentTimeMillis(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
	}
	
	public LinkStatistics(StatsChangeTracker tracker){
		this(tracker, System.currentTimeMillis(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
	}
	
	/** Contains a bunch on-modification callbacks, if you want to pipe stats over somewhere - overload them. */
	public static class StatsChangeTracker {
		public void dataSentChanged(long previousval, long newval, long time) {
	    }
		public void usefullPaybackSentChanged(long previousval, long newval, long time) {
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
	
    public StatsChangeTracker Tracker;
    
    /* Resets statistics and assigns new tracker */
    public void attachListener(StatsChangeTracker t) {
    	synchronized (this) {
    		reset();
    		seriousBackoffs = 0;
    		Tracker = t;
    	}
    }

	protected long lastUpdated;
	/* The last time reset() was called */
	protected long lastReset;
	
	/** Inside part - tracked directly in PeerNode */
	
	/* TODO: Have a look at RunningAverage 's, perhaps utilize some of those? e.g. for bandwidth */
	
	protected long dataSent;
	/** This excludes all the additional headers, acks, etc. Counts pure transmitted payback. In bytes */
	protected long usefullPaybackSent;
	/** Amount of data (in bytes) that we received an ack for. 
	 *  Includes only acknowledged data that was transmitted after the last reset */
	protected long dataAcked;
	/** How many times did we retransmit a packet */
	protected long retransmitCount;
	/** How much data did we retransmit */
	protected long dataRetransmitted;
	/** How many times did we back off. Note that it will always be zero upon reset, e.g. due to backoff */
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
		dataSent = usefullPaybackSent = dataAcked = dataRetransmitted = 0;
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
	public long getUsefullPaybackSent(){
        return usefullPaybackSent;
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
        synchronized (this) {
            lastUpdated = System.currentTimeMillis();
            long previousval = dataSent;
            dataSent += amount;
            Tracker.dataSentChanged(previousval, dataSent, lastUpdated);
        }
    }
	public void onUsefullPaybackSend(long amount){
        synchronized (this) {
            lastUpdated = System.currentTimeMillis();
            long previousval = usefullPaybackSent;
            usefullPaybackSent += amount;
            Tracker.usefullPaybackSentChanged(previousval, usefullPaybackSent, lastUpdated);
        }
    }
	/**
	 * @param amount - amount of data acknowledged
	 * @param whenSent - time when the acknowledged data was last sent or retransmitted  
	 */
	public void onNewDataAck(long amount, long whenSent){
        synchronized (this) {
            if (whenSent > lastReset) {
			    lastUpdated = System.currentTimeMillis();
	            long previousval = dataAcked;
	            dataAcked += amount;
                Tracker.acksSentChanged(previousval, dataAcked, lastUpdated); 
            }
        }
    }
	public void onDataRetransmit(long amount){
        synchronized (this) {
            lastUpdated = System.currentTimeMillis();
            ++retransmitCount;
            long previousval = dataRetransmitted;
            dataRetransmitted += amount;
            Tracker.dataRetransmittedChanged(previousval, dataRetransmitted, lastUpdated);
        }
    }
	public void onSeriousBackoff(long amount){
        synchronized (this) {
            lastUpdated = System.currentTimeMillis();
            long previousval = seriousBackoffs;
            seriousBackoffs += amount;
            Tracker.seriousBackoffsChanged(previousval, seriousBackoffs, lastUpdated);
        }
    }
	public void onQueueBacklogChange(double newval){ // For later use
        synchronized (this) {
            lastUpdated = System.currentTimeMillis();
            double previousval = queueBacklog;
            queueBacklog += newval;
            Tracker.queueBacklogChanged(previousval, queueBacklog, lastUpdated);
        }
    }
	public void onWindowSizeChange(double newval){
        synchronized (this) {
            lastUpdated = System.currentTimeMillis();
            double previousval = windowSize;
            windowSize = newval;
            Tracker.windowSizeChanged(previousval, windowSize, lastUpdated);
        }
    }
	public void onMaxUsedWindowChange(double newval){ // For later use
        synchronized (this) {
            lastUpdated = System.currentTimeMillis();
            double previousval = maxUsedWindow;
            maxUsedWindow = newval;
            Tracker.maxUsedWindowChanged(previousval, maxUsedWindow, lastUpdated);
        }
    }
	public void onAverageRTTChange(double newval){
        synchronized (this) {
            lastUpdated = System.currentTimeMillis();
            double previousval = averageRTT;
            averageRTT = newval;
            Tracker.averageRTTChanged(previousval, averageRTT, lastUpdated);
        }
    }
	public void onRTOChange(double newval){
        synchronized (this) {
            lastUpdated = System.currentTimeMillis();
            double previousval = RTO;
            RTO = newval;
            Tracker.RTOChanged(previousval, RTO, lastUpdated);
        }
    }

}
