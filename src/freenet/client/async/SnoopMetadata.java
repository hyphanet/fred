package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.client.Metadata;

public interface SnoopMetadata {

	/** Spy on the metadata as a file is being fetched. Return true to cancel the request. */
	public boolean snoopMetadata(Metadata meta, ObjectContainer container, ClientContext context);
	
}
