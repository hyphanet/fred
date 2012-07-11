package freenet.pluginmanager;

/**
 * When a transport plugin has been incorrectly created.
 * <li> Transport Name is not unique.</li>
 * <li> Transport Name is null. </li>
 * <li> Transport registering in wrong mode of operation(opennet, darknet mode).</li>
 * <li> Wrong Transport trying to be added as defaultTransport.</li>
 * @author chetan
 *
 */
public class FaultyTransportPluginException extends Exception {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	
	public final String errorMessage;
	
	public FaultyTransportPluginException(String errorMessage){
		super(errorMessage);
		this.errorMessage = errorMessage;
	}

}
