package freenet.support.compress;

import freenet.client.InsertException;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientPutState;

public interface CompressJob {
	public abstract void tryCompress(ClientContext context) throws InsertException;
	public abstract void onFailure(InsertException e, ClientPutState c, ClientContext context);
}
