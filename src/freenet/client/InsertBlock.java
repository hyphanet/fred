package freenet.client;

import freenet.support.Bucket;

/**
 * Class to contain everything needed for an insert.
 */
public class InsertBlock {

	private Bucket data;
	private String mimeType;
	
	public InsertBlock(Bucket data, String mimeType) {
		this.data = data;
		this.mimeType = mimeType;
	}

}
