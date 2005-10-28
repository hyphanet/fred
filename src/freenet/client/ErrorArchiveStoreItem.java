package freenet.client;

import freenet.keys.FreenetURI;

class ErrorArchiveStoreItem extends ArchiveStoreItem {

	String error;
	
	public ErrorArchiveStoreItem(ArchiveStoreContext ctx, FreenetURI key2, String name, String error) {
		super(new ArchiveKey(key2, name), ctx);
		this.error = error;
	}

	public void finalize() {
		super.finalize();
	}
	
}
