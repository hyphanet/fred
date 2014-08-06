package freenet.client.async;

import freenet.client.Metadata;

public class DumperSnoopMetadata implements SnoopMetadata {

	@Override
	public boolean snoopMetadata(Metadata meta, ClientContext context) {
		System.err.print(meta.dump());
		return false;
	}

}
