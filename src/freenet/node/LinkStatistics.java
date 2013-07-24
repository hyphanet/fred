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
	
	public LinkStatistics(long lastupdated, long lastreset, int datasent, int usefullpaybacksent, int dataacked,
						  int dataretransmitted, int retransmitcount, int seriousbackoffs, 
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
	
	public LinkStatistics(StatsChangeTracker tracker, long lastupdated, long lastreset, int datasent, int usefullpaybacksent, 
					      int dataacked, int dataretransmitted, int retransmitcount, int seriousbackoffs, 
			  			  double queuebacklog, double windowsize, double maxusedwindow, int averagertt, int rto) {
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
		
		public void dataSentChanged(int previousval, int newval){
	    }
		public void usefullPaybackSentChanged(int previousval, int newval){
	    }
		public void acksSentChanged(int previousval, int newval){
	    }
		public void pureAcksSentChanged(int previousval, int newval){
	    }
		public void dataRetransmittedChanged(int previousval, int newval){
	    }
		public void seriousBackoffsChanged(int previousval, int newval){
	    }
		public void queueBacklogChanged(double previousval, double newval){
	    }
		public void windowSizeChanged(double previousval, double newval){
	    }
		public void maxUsedWindowChanged(double previousval, double newval){
	    }
		public void averageRTTChanged(double previousval, double newval){
	    }
		public void RTOChanged(double previousval, double newval){
	    }
	}
	
    public StatsChangeTracker Tracker;

	protected long lastUpdated;
	/* The last time reset() was called */
	protected long lastReset;
	
	/** Inside part - tracked directly in PeerNode */
	
	/* TODO: Have a look at RunningAverage 's, perhaps utilize some of those? */
	
	protected int dataSent;
	/** This excludes all the additional headers, acks, etc. Counts pure transmitted payback. In bytes */
	protected int usefullPaybackSent;
	/** Amount of data (in bytes) that we received an ack for. 
	 *  Includes only acknowledged data that was transmitted after the last reset */
	protected int dataAcked;
	/** How many times did we retransmit a packet */
	protected int retransmitCount;
	/** How much data did we retransmit */
	protected int dataRetransmitted;
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
	protected double averageRTT;
	/** See PacketThrottle */
	protected double RTO;
	
	
	public void reset(){
		lastUpdated = System.currentTimeMillis();
        lastReset = lastUpdated;
		dataSent = usefullPaybackSent = dataAcked = dataRetransmitted = 
				seriousBackoffs = 0;
        queueBacklog = windowSize = maxUsedWindow = RTO = averageRTT = 0.0;
	}
	
    public long getLastUpdated(){
        return lastUpdated;
    }
    public long getLastReset(){
        return lastReset;
    }
	public int getDataSent(){
        return dataSent;
    }
	public int getUsefullPaybackSent(){
        return usefullPaybackSent;
    }
	public int getDataAcked(){
        return dataAcked;
    }
	public int getDataRetransmitted(){
        return dataRetransmitted;
    }
	public int getRetransmitCount(){
		return retransmitCount;
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
	public double getAverageRTT(){
        return averageRTT;
    }
	public double getRTO(){
        return RTO;
    }
    
	public void onDataSend(int amount){
        lastUpdated = System.currentTimeMillis();
        int previousval = dataSent;
        dataSent += amount;
        Tracker.dataSentChanged(previousval, dataSent);
    }
	public void onUsefullPaybackSend(int amount){
        lastUpdated = System.currentTimeMillis();
        int previousval = usefullPaybackSent;
        usefullPaybackSent += amount;
        Tracker.usefullPaybackSentChanged(previousval, usefullPaybackSent);
    }
	/**
	 * @param amount - amount of data acknowledged
	 * @param whenSent - time when the acknowledged data was last sent or retransmitted  
	 */
	public void onNewDataAcked(int amount, long whenSent){
        if (whenSent > lastReset) {
			lastUpdated = System.currentTimeMillis();
	        int previousval = dataAcked;
	        dataAcked += amount;
	        Tracker.acksSentChanged(previousval, dataAcked); 
        }
    }
	public void onDataRetransmit(int amount){
        lastUpdated = System.currentTimeMillis();
        ++retransmitCount;
        int previousval = dataRetransmitted;
        dataRetransmitted += amount;
        Tracker.dataRetransmittedChanged(previousval, dataRetransmitted);
    }
	public void onSeriousBackoff(int amount){
        lastUpdated = System.currentTimeMillis();
        int previousval = seriousBackoffs;
        seriousBackoffs += amount;
        Tracker.seriousBackoffsChanged(previousval, seriousBackoffs);
    }
	public void onQueueBacklogChange(double newval){ // For later use
        lastUpdated = System.currentTimeMillis();
        double previousval = queueBacklog;
        queueBacklog += newval;
        Tracker.queueBacklogChanged(previousval, queueBacklog);
    }
	public void onWindowSizeChange(double newval){
        lastUpdated = System.currentTimeMillis();
        double previousval = windowSize;
        windowSize = newval;
        Tracker.windowSizeChanged(previousval, windowSize);
    }
	public void onMaxUsedWindowChange(double newval){ // For later use
        lastUpdated = System.currentTimeMillis();
        double previousval = maxUsedWindow;
        maxUsedWindow = newval;
        Tracker.maxUsedWindowChanged(previousval, maxUsedWindow);
    }
	public void onAverageRTTChange(double newval){
        lastUpdated = System.currentTimeMillis();
        double previousval = averageRTT;
        averageRTT = newval;
        Tracker.averageRTTChanged(previousval, averageRTT);
    }
	public void onRTOChange(double newval){
        lastUpdated = System.currentTimeMillis();
        double previousval = RTO;
        RTO = newval;
        Tracker.RTOChanged(previousval, RTO);
    }

}
