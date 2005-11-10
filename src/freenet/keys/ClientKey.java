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
		throw new UnsupportedOperationException("Unknown keytype from "+origURI);
	}

	/**
	 * Does the key contain metadata? If not, it contains real data.
	 */
	public abstract boolean isMetadata();

	public abstract FreenetURI getURI();

}
