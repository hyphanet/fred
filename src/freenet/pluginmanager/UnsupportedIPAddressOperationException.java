package freenet.pluginmanager;

/**
 * This exception is thrown by a PluginAddress object when an operation is not supported.
 * All IP based addresses must support the methods, while a few might not.
 * In general non IP based addresses will throw this exception and the usual checking that is done
 * for IP based addresses will be skipped. If the plugin/transport can support an equivalent operation
 * then they those methods in PluginAddress can be used without throwing this exception.
 * <br><br>
 * Please refer to Peer/PeerPluginAddress for what these operations exactly do in the case of IP based addresses.
 * @author chetan
 *
 */
public class UnsupportedIPAddressOperationException extends Exception {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	public final String errorMessage;;
	
	public UnsupportedIPAddressOperationException(String errorMessage) {
		super(errorMessage);
		this.errorMessage = errorMessage;
	}

}
