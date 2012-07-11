package freenet.pluginmanager;

/**
 * Thrown when an initialisation failed, because it could not bind or an instance was already running.
 * @author chetan
 *
 */
public class TransportInitException extends Exception {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	
	public final String errorMessage;
	
	public TransportInitException(String errorMessage){
		super(errorMessage);
		this.errorMessage = errorMessage;
	}

}
