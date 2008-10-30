package freenet.support.compress;

import freenet.client.InsertException;
import freenet.client.async.ClientPutState;

public interface CompressJob {
	public abstract void tryCompress() throws InsertException;
	public abstract void onFailure(InsertException e, ClientPutState c);
}
