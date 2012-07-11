package freenet.pluginmanager;

/**
 * Thrown when a configuration for a plugin was not present or could not be parsed/constructed.
 * @author chetan
 *
 */
public class TransportPluginConfigurationException extends Exception{
	
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	
	public final String errorMessage;
	
	public TransportPluginConfigurationException(String errorMessage){
		super(errorMessage);
		this.errorMessage = errorMessage;
	}
	
}
