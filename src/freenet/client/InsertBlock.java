package freenet.client;

import freenet.keys.FreenetURI;
import freenet.support.Bucket;

/**
 * Class to contain everything needed for an insert.
 */
public class InsertBlock {

	Bucket data;
	public final FreenetURI desiredURI;
	public final ClientMetadata clientMetadata;
	
	public InsertBlock(Bucket data, ClientMetadata metadata, FreenetURI desiredURI) {
		this.data = data;
		if(metadata == null)
			clientMetadata = new ClientMetadata();
		else
			clientMetadata = metadata;
		this.desiredURI = desiredURI;
	}
	
	public Bucket getData() {
		return data;
	}

}
