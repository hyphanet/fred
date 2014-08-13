package freenet.keys;

import java.io.Serializable;

/**
 * Base class for client keys.
 * Client keys are decodable. Node keys are not. When data has been fetched
 * to a node-level KeyBlock, it can only be decoded after a ClientKeyBlock
 * has been constructed from the node-level block and the client key. The
 * client key generally contains the encryption keys which the node level
 * does not know about, but which are in the URI - usually the second part,
 * after the comma.
 */
public abstract class ClientKey extends BaseClientKey implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
	 * @return a NodeCHK corresponding to this key. Basically keep the 
	 * routingKey and lose everything else.
	 */
	public abstract Key getNodeKey(boolean cloneKey);
	
	public Key getNodeKey() {
		return getNodeKey(true);
	}

	public abstract ClientKey cloneKey();
	
	protected ClientKey() {
	    // For serialization.
	}

}
