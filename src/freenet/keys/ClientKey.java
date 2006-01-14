package freenet.keys;

import java.net.MalformedURLException;

/**
 * Base class for client keys.
 * Client keys are decodable. Node keys are not.
 */
public abstract class ClientKey {

	public static ClientKey getBaseKey(FreenetURI origURI) throws MalformedURLException {
		if(origURI.getKeyType().equals("CHK"))
			return new ClientCHK(origURI);
		if(origURI.getKeyType().equals("SSK"))
			return new ClientSSK(origURI);
		if(origURI.getKeyType().equals("KSK"))
			return ClientKSK.create(origURI.getDocName());
		throw new UnsupportedOperationException("Unknown keytype from "+origURI);
	}
	
	public abstract FreenetURI getURI();

	/**
	 * @return a NodeCHK corresponding to this key. Basically keep the 
	 * routingKey and lose everything else.
	 */
	public abstract Key getNodeKey();

}
