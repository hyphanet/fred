package freenet.node;

/**
 * @author quadrocube
 * Contains a bunch on-modification callbacks, if you want to pipe stats over somewhere - overload them. 
 */
public class StatsChangeTracker {
	
	public void dataSentChanged(int previousval, int newval){
    }
	public void usefullPaybackSentChanged(int previousval, int newval){
    }
	public void acksSentChanged(int previousval, int newval){
    }
	public void pureAcksSentChanged(int previousval, int newval){
    }
	public void packetsRetransmittedChanged(int previousval, int newval){
    }
	public void seriousBackoffsChanged(int previousval, int newval){
    }
	public void queueBacklogChanged(double previousval, double newval){
    }
	public void windowSizeChanged(double previousval, double newval){
    }
	public void maxUsedWindowChanged(double previousval, double newval){
    }
	public void averageRTTChanged(int previousval, int newval){
    }
	public void RTOChanged(int previousval, int newval){
    }
}
