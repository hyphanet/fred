package freenet.client.async;

import com.db4o.ObjectContainer;

public interface USKFetcherTagCallback extends USKFetcherCallback {
	
	public void setTag(USKFetcherTag tag, ObjectContainer container, ClientContext context);

}
