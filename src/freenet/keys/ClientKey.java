package freenet.keys;

/**
 * Base class for client keys.
 * Client keys are decodable. Node keys are not.
 */
public abstract class ClientKey {

	public static ClientKey getBaseKey(FreenetURI origURI) {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * Does the key contain metadata? If not, it contains real data.
	 */
	public abstract boolean isMetadata();

	public abstract FreenetURI getURI();

}
