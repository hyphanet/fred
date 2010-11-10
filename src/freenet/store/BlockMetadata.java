package freenet.store;

/** Metadata returned from the datastore along with a block */
public final class BlockMetadata {

	/** If true, the block is old, that is, it was added to the store prior to 1224,
	 * or it was added since but only because low physical seclevel caused everything
	 * to be cached. In other words, if this is true, we cannot be sure that the block
	 * *should* be cached, so others should only know about it if we are actually 
	 * transmitting the data. */
	private boolean oldBlock;
	
	public final void reset() {
		oldBlock = false;
	}
	
	/** If true, the block should not be cached i.e. it was either added before 1224, or
	 * it was only cached because of writing everything to the datastore including local
	 * and nearby requests. */
	public final boolean isOldBlock() {
		return oldBlock;
	}
	
	public final void setOldBlock() {
		oldBlock = true;
	}

}
