/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
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
