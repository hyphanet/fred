/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import com.db4o.ObjectContainer;

import freenet.client.events.ClientEventProducer;
import freenet.client.events.SimpleEventProducer;
import freenet.support.Logger;
import freenet.support.compress.Compressor;

/** Context object for an insert operation, including both simple and multi-file inserts */
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public class InsertContext implements Cloneable {

	/** If true, don't try to compress the data */
	public boolean dontCompress;
	/** Splitfile algorithm. */
	public final short splitfileAlgorithm;
	/** Maximum number of retries (after the initial attempt) for each block
	 * inserted. -1 = retry forever or until it succeeds (subject to 
	 * consecutiveRNFsCountAsSuccess) or until a fatal error. */
	public int maxInsertRetries;
	/** On a very small network, any insert will RNF. Therefore we allow 
	 * some number of RNFs to equal success. */
	public final int consecutiveRNFsCountAsSuccess;
	/** Maximum number of data blocks per segment for splitfiles */
	public final int splitfileSegmentDataBlocks;
	/** Maximum number of check blocks per segment for splitfiles. Will be reduced proportionally if there
	 * are fewer data blocks. */
	public final int splitfileSegmentCheckBlocks;
	/** Client events will be published to this, you can subscribe to them */
	public final ClientEventProducer eventProducer;
	/** Can this insert write to the client-cache? We don't store all requests in the client cache,
	 * in particular big stuff usually isn't written to it, to maximise its effectiveness. Plus, 
	 * local inserts are not written to the client-cache by default for privacy reasons. */
	public boolean canWriteClientCache;
	/** a string that contains the codecs to use/try
	 * if the string is null it defaults to COMPRESSOR_TYPES.Values(),
	 * so old persistent inserts are not affected after update.
	 * @see Compressor.COMPRESSOR_TYPES#getCompressorsArray(String compressordescriptor)
	 */
	public String compressorDescriptor;
	public boolean forkOnCacheable;
	/** Number of extra inserts for a single block inserted on its own. */
	public int extraInsertsSingleBlock;
	/** Number of extra inserts for a block inserted above a splitfile. */
	public int extraInsertsSplitfileHeaderBlock;
	public boolean localRequestOnly;
	// FIXME DB4O: This should really be an enum. However, db4o has a tendency to copy enum's,
	// which wastes space (often unrecoverably), confuses programmers, creates wierd bugs and breaks == comparison.
	
	public static enum CompatibilityMode {
		/** We do not know. */
		COMPAT_UNKNOWN,
		/** No compatibility issues, use the most efficient metadata possible. 
		 * Used only for configuring an insert, *NOT* in Metadata compatibility mode detection. */
		COMPAT_CURRENT,
		// The below *are* used in Metadata compatibility mode detection. And they are comparable by ordinal().
		// This means we have to check for COMPAT_CURRENT as a special case.
		/** Exactly as before 1250: Segments of exactly 128 data, 128 check, check = data */
		COMPAT_1250_EXACT,
		/** 1250 or previous: Segments up to 128 data 128 check, check <= data. */
		COMPAT_1250,
		/** 1251/2/3: Basic even splitting, 1 extra check block if data blocks < 128, max 131 data blocks. */
		COMPAT_1251,
		/** 1255: Second stage of even splitting, a whole bunch of segments lose one block rather than the last segment losing lots of blocks. And hashes too! */
		COMPAT_1255,
		/** 1416: New CHK encryption */
		COMPAT_1416;
		
		// Inserts should be converted to a specific compatibility mode as soon as possible, to avoid
		// problems when an insert is restarted on a newer build with a newer default compat mode.
		public static CompatibilityMode latest() {
			CompatibilityMode[] values = values();
			return values[values.length-1];
		}
	}
	
	/** Backward compatibility support for network level metadata. 
	 * Not an enum because of back compatibility and because db4o tends to do bad things to enums i.e. copy the values. */
	private long compatibilityMode;
	
	public CompatibilityMode getCompatibilityMode() {
		return CompatibilityMode.values()[(int)compatibilityMode];
	}
	
	public long getCompatibilityCode() {
		return compatibilityMode;
	}

	public void setCompatibilityMode(CompatibilityMode mode) {
		if(mode == CompatibilityMode.COMPAT_CURRENT)
			mode = CompatibilityMode.latest();
		this.compatibilityMode = mode.ordinal();
	}

	public InsertContext(
			int maxRetries, int rnfsToSuccess, int splitfileSegmentDataBlocks, int splitfileSegmentCheckBlocks,
			ClientEventProducer eventProducer, boolean canWriteClientCache, boolean forkOnCacheable, boolean localRequestOnly, String compressorDescriptor, int extraInsertsSingleBlock, int extraInsertsSplitfileHeaderBlock, CompatibilityMode compatibilityMode) {
		dontCompress = false;
		splitfileAlgorithm = Metadata.SPLITFILE_ONION_STANDARD;
		this.consecutiveRNFsCountAsSuccess = rnfsToSuccess;
		this.maxInsertRetries = maxRetries;
		this.eventProducer = eventProducer;
		this.splitfileSegmentDataBlocks = splitfileSegmentDataBlocks;
		this.splitfileSegmentCheckBlocks = splitfileSegmentCheckBlocks;
		this.canWriteClientCache = canWriteClientCache;
		this.forkOnCacheable = forkOnCacheable;
		this.compressorDescriptor = compressorDescriptor;
		this.extraInsertsSingleBlock = extraInsertsSingleBlock;
		this.extraInsertsSplitfileHeaderBlock = extraInsertsSplitfileHeaderBlock;
		if(compatibilityMode == CompatibilityMode.COMPAT_CURRENT)
			compatibilityMode = CompatibilityMode.latest();
		this.compatibilityMode = compatibilityMode.ordinal();
		this.localRequestOnly = localRequestOnly;
	}

	public InsertContext(InsertContext ctx, SimpleEventProducer producer) {
		this.dontCompress = ctx.dontCompress;
		this.splitfileAlgorithm = ctx.splitfileAlgorithm;
		this.consecutiveRNFsCountAsSuccess = ctx.consecutiveRNFsCountAsSuccess;
		this.maxInsertRetries = ctx.maxInsertRetries;
		this.eventProducer = producer;
		this.splitfileSegmentDataBlocks = ctx.splitfileSegmentDataBlocks;
		this.splitfileSegmentCheckBlocks = ctx.splitfileSegmentCheckBlocks;
		this.compressorDescriptor = ctx.compressorDescriptor;
		this.forkOnCacheable = ctx.forkOnCacheable;
		this.extraInsertsSingleBlock = ctx.extraInsertsSingleBlock;
		this.extraInsertsSplitfileHeaderBlock = ctx.extraInsertsSplitfileHeaderBlock;
		this.compatibilityMode = ctx.compatibilityMode;
		this.localRequestOnly = ctx.localRequestOnly;
	}
	
	/** Make public, but just call parent for a field for field copy */
	@Override
	public InsertContext clone() {
		try {
			return (InsertContext) super.clone();
		} catch (CloneNotSupportedException e) {
			// Impossible
			throw new Error(e);
		}
	}

	public void removeFrom(ObjectContainer container) {
		if(eventProducer == null) {
			Logger.error(this, "No EventProducer on InsertContext! activated="+container.ext().isActive(this)+" stored="+container.ext().isStored(this), new Exception("error"));
		} else {
			container.activate(eventProducer, 1);
			eventProducer.removeFrom(container);
		}
		container.delete(this);
	}

}
