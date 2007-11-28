package freenet.io;

public interface PortForwardBrokenDetector {

	/** @return True if there is a good reason to think that port forwarding is broken. */
	boolean isBroken();

}
