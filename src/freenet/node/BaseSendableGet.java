package freenet.node;

import freenet.keys.Key;

public abstract class BaseSendableGet extends SendableRequest {
	
	/** Get a numbered key to fetch. */
	public abstract Key getNodeKey(Object token);
	
}
