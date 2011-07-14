package freenet.support.CPUInformation;

/**
 * @author Iakin
 * Created on Jul 16, 2004
 */
public class UnknownCPUException extends RuntimeException {
	private static final long serialVersionUID = -1;
	public UnknownCPUException() {
		super();
	}

	public UnknownCPUException(String message) {
		super(message);
	}
}
