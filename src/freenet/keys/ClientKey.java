package freenet.keys;

import java.net.MalformedURLException;

/**
 * Base class for client keys.
 * Client keys are decodable. Node keys are not.
 */
public abstract class ClientKey extends BaseClientKey {

	/**
	 * @return a NodeCHK corresponding to this key. Basically keep the 
	 * routingKey and lose everything else.
	 */
	public abstract Key getNodeKey();

}
