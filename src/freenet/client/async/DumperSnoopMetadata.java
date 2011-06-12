package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.client.Metadata;

public class DumperSnoopMetadata implements SnoopMetadata {

	@Override
	public boolean snoopMetadata(Metadata meta, ObjectContainer container, ClientContext context) {
		System.err.print(meta.dump());
		return false;
	}

}
