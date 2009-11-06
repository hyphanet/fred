package freenet.store;

/** Metadata returned from the datastore along with a block */
public final class BlockMetadata {

	/** If true, there is no metadata known for this block, e.g. because the store
	 * doesn't support it. */
	boolean noMetadata;
	
	/** If true, the block is old, that is, it was added to the store prior to 1224,
	 * or it was added since but only because low physical seclevel caused everything
	 * to be cached. In other words, if this is true, we cannot be sure that the block
	 * *should* be cached, so others should only know about it if we are actually 
	 * transmitting the data. */
	boolean oldBlock;
	
	public final void reset() {
		oldBlock = false;
	}
	
	public final boolean isOldBlock() {
		return oldBlock;
	}
	
	public final boolean noMetadata() {
		return noMetadata;
	}

	public final void setOldBlock() {
		oldBlock = true;
	}

}
