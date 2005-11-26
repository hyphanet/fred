package freenet.node;

import freenet.support.Logger;
import freenet.support.math.BootstrappingDecayingRunningAverage;

/**
 * Keeps track of the current request send rate.
 */
public class RequestThrottle {

	protected static final float PACKET_DROP_DECREASE_MULTIPLE = 0.97f;
	protected static final float PACKET_TRANSMIT_INCREMENT = (4 * (1 - (PACKET_DROP_DECREASE_MULTIPLE * PACKET_DROP_DECREASE_MULTIPLE))) / 3;
	protected static final long MAX_DELAY = 5*60*1000;
	protected static final long MIN_DELAY = 20;
	public static final long DEFAULT_DELAY = 200;
	private long _totalPackets = 0, _droppedPackets = 0;
	private double _simulatedWindowSize = 2;
	private final BootstrappingDecayingRunningAverage roundTripTime; 

	RequestThrottle(long rtt, float winSize) {
		_simulatedWindowSize = 2;
		roundTripTime = new BootstrappingDecayingRunningAverage(rtt, 10, 5*60*1000, 10);
	}
	
	/**
	 * Get the current inter-request delay.
	 */
	public synchronized long getDelay() {
		double rtt = roundTripTime.currentValue();
		double winSizeForMinPacketDelay = rtt / MIN_DELAY;
		if (_simulatedWindowSize > winSizeForMinPacketDelay) {
			_simulatedWindowSize = winSizeForMinPacketDelay;
		}
		if (_simulatedWindowSize < 1.0) {
			_simulatedWindowSize = 1.0F;
		}
		// return (long) (_roundTripTime / _simulatedWindowSize);
		return Math.max(MIN_DELAY, Math.min((long) (rtt / _simulatedWindowSize), MAX_DELAY));
	}

	/**
	 * Report that a request completed successfully, and the
	 * time it took.
	 */
	public synchronized void requestCompleted(long time) {
		setRoundTripTime(time);
        _totalPackets++;
        _simulatedWindowSize += PACKET_TRANSMIT_INCREMENT;
	}

	/**
	 * Report that a request got RejectedOverload.
	 * Do not report the time it took, because it is irrelevant.
	 */
	public synchronized void requestRejectedOverload() {
		_droppedPackets++;
		_totalPackets++;
		_simulatedWindowSize *= PACKET_DROP_DECREASE_MULTIPLE;
	}
	
	private synchronized void setRoundTripTime(long rtt) {
		roundTripTime.report(Math.max(rtt, 10));
		Logger.minor(this, "Reporting RTT: "+rtt);
	}

	public synchronized String toString() {
		return  getDelay()+" ms, (w: "
				+ _simulatedWindowSize + ", r:" + roundTripTime.currentValue() + ", d:"
				+ (((float) _droppedPackets / (float) _totalPackets)) + "="+_droppedPackets+"/"+_totalPackets + ")";
	}
}
