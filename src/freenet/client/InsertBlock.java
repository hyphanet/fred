package freenet.client;

import freenet.keys.FreenetURI;
import freenet.support.io.Bucket;

/**
 * Class to contain everything needed for an insert.
 */
public class InsertBlock {

	private final Bucket data;
	public final FreenetURI desiredURI;
	public final ClientMetadata clientMetadata;
	
	public InsertBlock(Bucket data, ClientMetadata metadata, FreenetURI desiredURI) {
		if(data == null) throw new NullPointerException();
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
