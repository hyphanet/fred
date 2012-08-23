package freenet.pluginmanager;

/**
 * Thrown when a String could not be converted to a PluginAddress by the transport plugin. </br>
 * Thrown when a PluginAddress is not the instance the TransportPlugin created. </br>
 * Thrown when a PluginAddress is null. </br>
 * @author chetan
 *
 */
public class MalformedPluginAddressException extends Exception {
	
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	
	public final String errorMessage;
	
	public MalformedPluginAddressException(String errorMessage){
		super(errorMessage);
		this.errorMessage = errorMessage;
	}

}
