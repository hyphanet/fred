package freenet.pluginmanager;
/**
 * Thrown when a requested transportName String did not find a transport plugin.
 * @author chetan
 *
 */
public class TransportPluginException extends Exception{

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	
	public final String errorMessage;
	
	public TransportPluginException(String errorMessage){
		super(errorMessage);
		this.errorMessage = errorMessage;
	}
	

}
