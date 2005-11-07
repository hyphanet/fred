package freenet.client;

import freenet.keys.FreenetURI;
import freenet.support.Bucket;

/**
 * Class to contain everything needed for an insert.
 */
public class InsertBlock {

	final Bucket data;
	final FreenetURI desiredURI;
	final ClientMetadata clientMetadata;
	
	public InsertBlock(Bucket data, ClientMetadata metadata, FreenetURI desiredURI) {
		this.data = data;
		clientMetadata = metadata;
		this.desiredURI = desiredURI;
	}

}
