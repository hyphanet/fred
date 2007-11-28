package freenet.io.comm;

public interface PortForwardSensitiveSocketHandler extends SocketHandler {

	/** Something has changed at a higher level suggesting the port forwarding status may be bogus,
	 * so we need to rescan. */
	void rescanPortForward();

}
