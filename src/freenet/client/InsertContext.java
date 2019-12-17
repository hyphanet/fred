/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import freenet.client.Metadata.SplitfileAlgorithm;
import freenet.client.events.ClientEventProducer;
import freenet.client.events.SimpleEventProducer;

/** Context object for an insert operation, including both simple and multi-file inserts.
 * 
 * WARNING: Changing non-transient members on classes that are Serializable can result in 
 * losing uploads. 
 */
public class InsertContext implements Cloneable, Serializable {

    private static final long serialVersionUID = 1L;
    /** If true, don't try to compress the data */
	public boolean dontCompress;
	/** Splitfile algorithm. */
	private SplitfileAlgorithm splitfileAlgo;
	/** For migration only. */
	private final short splitfileAlgorithm;
	/** Maximum number of retries (after the initial attempt) for each block
	 * inserted. -1 = retry forever or until it succeeds (subject to 
	 * consecutiveRNFsCountAsSuccess) or until a fatal error. */
	public int maxInsertRetries;
	/** On a very small network, any insert will RNF. Therefore we allow 
	 * some number of RNFs to equal success. */
	public int consecutiveRNFsCountAsSuccess;
	/** Maximum number of data blocks per segment for splitfiles */
	public int splitfileSegmentDataBlocks;
	/** Maximum number of check blocks per segment for splitfiles. Will be reduced proportionally if there
	 * are fewer data blocks. */
	public int splitfileSegmentCheckBlocks;
	/** Client events will be published to this, you can subscribe to them */
	public final ClientEventProducer eventProducer;
	/** Can this insert write to the client-cache? We don't store all requests in the client cache,
	 * in particular big stuff usually isn't written to it, to maximise its effectiveness. Plus, 
	 * local inserts are not written to the client-cache by default for privacy reasons. */
	public boolean canWriteClientCache;
	/** a string that contains the codecs to use/try
	 * if the string is null it defaults to COMPRESSOR_TYPES.Values(),
	 * so old persistent inserts are not affected after update.
	 * @see freenet.support.compress.Compressor.COMPRESSOR_TYPE#getCompressorsArray(String)
	 */
	public String compressorDescriptor;
	public boolean forkOnCacheable;
	/** Number of extra inserts for a single block inserted on its own. */
	public int extraInsertsSingleBlock;
	/** Number of extra inserts for a block inserted above a splitfile. */
	public int extraInsertsSplitfileHeaderBlock;
	public boolean localRequestOnly;
	/** Don't insert USK DATEHINTs (and ignore them on polling for maximum edition). */
	public boolean ignoreUSKDatehints;

	/** Compatibility mode. This determines exactly how we insert data, so that we can produce the 
	 * same CHK when reinserting a key even if it is with a later version of Freenet. It is 
	 * also important for e.g. auto-update to be able to insert keys compatible with older nodes, 
	 * but CompatibilityMode's are sometimes backwards compatible, there are separate versioning
	 * systems for keys and Metadata, which will be set as appropriate for an insert depending on 
	 * the CompatibilityMode. */
	public static enum CompatibilityMode {
	    
		/** We do not know. */
		COMPAT_UNKNOWN((short)0),
		/** No compatibility issues, use the most efficient metadata possible. Used only in the 
		 * front-end: MUST NOT be stored: Code should convert this to a specific mode as early as
		 * possible, or inserts will break when a new mode is added. InsertContext does this. */
		COMPAT_CURRENT((short)1),
		// The below *are* used in Metadata compatibility mode detection. And they are comparable by ordinal().
		// This means we have to check for COMPAT_CURRENT as a special case.
		/** Exactly as before 1250: Segments of exactly 128 data, 128 check, check = data */
		COMPAT_1250_EXACT((short)2),
		/** 1250 or previous: Segments up to 128 data 128 check, check <= data. */
		COMPAT_1250((short)3),
		/** 1251/2/3: Basic even splitting, 1 extra check block if data blocks < 128, max 131 data blocks. */
		COMPAT_1251((short)4),
		/** 1255: Second stage of even splitting, a whole bunch of segments lose one block rather than the last segment losing lots of blocks. And hashes too! */
		COMPAT_1255((short)5),
		/** 1416: New CHK encryption */
		COMPAT_1416((short)6),
		/** 1468: Fill in topDontCompress and topCompatibilityMode on splitfiles. Same blocks, but
		 * slightly different metadata. */
		COMPAT_1468((short)7);
		
		/** Code used in metadata for this CompatibilityMode. Hence we can remove old 
		 * CompatibilityMode's, and it's also convenient. */
		public final short code;
		
		CompatibilityMode(short code) {
		    this.code = code;
		}
		
		/** cached values(). Never modify or pass this array to outside code! */
		private static final CompatibilityMode[] values = values();

		// Inserts should be converted to a specific compatibility mode as soon as possible, to avoid
		// problems when an insert is restarted on a newer build with a newer default compat mode.
		public static CompatibilityMode latest() {
			return values[values.length-1];
		}
		
		/** Must be called whenever we accept a CompatibilityMode as e.g. a config option. Converts
		 * the pseudo- */
		public CompatibilityMode intern() {
		    if(this == COMPAT_CURRENT) return latest();
		    return this;
		}
		
        private static final Map<Short, CompatibilityMode> modesByCode;
        
        static {
            HashMap<Short, CompatibilityMode> cmodes = new HashMap<Short, CompatibilityMode>();
            for(CompatibilityMode mode : CompatibilityMode.values) {
                if(cmodes.containsKey(mode.code)) throw new Error("Duplicated code!");
                cmodes.put(mode.code, mode);
            }
            modesByCode = Collections.unmodifiableMap(cmodes);
        }

	    public static CompatibilityMode byCode(short code) {
	        if(!modesByCode.containsKey(code)) throw new IllegalArgumentException();
	        return modesByCode.get(code);
	    }
	    
        public static boolean hasCode(short min) {
            return modesByCode.containsKey(min);
        }

        public static boolean maybeFutureCode(short code) {
            return code > latest().code;
        }
        
        /** The default compatibility mode for new inserts when it is not specified. Usually this
         * will be COMPAT_CURRENT (it will get converted into a specific mode later), but when a
         * new compatibility mode is deployed we may want to keep this at an earlier version to 
         * avoid a period when data inserted with the new/testing builds can't be fetched with 
         * earlier versions. */
        public static final CompatibilityMode COMPAT_DEFAULT = COMPAT_CURRENT;
        
	}
	
	/** Backward compatibility support for network level metadata. */
	private CompatibilityMode realCompatMode;
	/** Only for migration. FIXME remove. */
	private long compatibilityMode;
	/** If true, don't insert, just generate the CHK */
    public boolean getCHKOnly;
    /** If true, try to find the final URI as quickly as possible, and insert the upper layers as 
     * soon as we can, rather than waiting for the lower layers. The default behaviour is safer,
     * because an attacker can usually only identify the datastream once he has the top block, or 
     * once you have announced the key. */
    public boolean earlyEncode;
	
	public CompatibilityMode getCompatibilityMode() {
	    return realCompatMode;
	}
	
	public long getCompatibilityCode() {
		return realCompatMode.ordinal();
	}

	public void setCompatibilityMode(CompatibilityMode mode) {
		this.realCompatMode = mode.intern();
	}

	public InsertContext(
			int maxRetries, int rnfsToSuccess, int splitfileSegmentDataBlocks, int splitfileSegmentCheckBlocks,
			ClientEventProducer eventProducer, boolean canWriteClientCache, boolean forkOnCacheable, boolean localRequestOnly, String compressorDescriptor, int extraInsertsSingleBlock, int extraInsertsSplitfileHeaderBlock, CompatibilityMode compatibilityMode) {
		dontCompress = false;
		splitfileAlgo = SplitfileAlgorithm.ONION_STANDARD;
		splitfileAlgorithm = splitfileAlgo.code;
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
		this.realCompatMode = compatibilityMode.intern();
		this.localRequestOnly = localRequestOnly;
		this.ignoreUSKDatehints = false;
	}

	public InsertContext(InsertContext ctx, SimpleEventProducer producer) {
		this.dontCompress = ctx.dontCompress;
		this.splitfileAlgo = ctx.splitfileAlgo;
        splitfileAlgorithm = splitfileAlgo.code;
		this.consecutiveRNFsCountAsSuccess = ctx.consecutiveRNFsCountAsSuccess;
		this.maxInsertRetries = ctx.maxInsertRetries;
		this.eventProducer = producer;
		this.splitfileSegmentDataBlocks = ctx.splitfileSegmentDataBlocks;
		this.splitfileSegmentCheckBlocks = ctx.splitfileSegmentCheckBlocks;
		this.compressorDescriptor = ctx.compressorDescriptor;
		this.forkOnCacheable = ctx.forkOnCacheable;
		this.extraInsertsSingleBlock = ctx.extraInsertsSingleBlock;
		this.extraInsertsSplitfileHeaderBlock = ctx.extraInsertsSplitfileHeaderBlock;
		this.realCompatMode = ctx.realCompatMode;
		this.localRequestOnly = ctx.localRequestOnly;
		this.ignoreUSKDatehints = ctx.ignoreUSKDatehints;
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
	
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (canWriteClientCache ? 1231 : 1237);
        result = prime * result + realCompatMode.ordinal();
        result = prime * result
                + ((compressorDescriptor == null) ? 0 : compressorDescriptor.hashCode());
        result = prime * result + consecutiveRNFsCountAsSuccess;
        result = prime * result + (dontCompress ? 1231 : 1237);
        // eventProducer is ignored.
        result = prime * result + extraInsertsSingleBlock;
        result = prime * result + extraInsertsSplitfileHeaderBlock;
        result = prime * result + (forkOnCacheable ? 1231 : 1237);
        result = prime * result + (ignoreUSKDatehints ? 1231 : 1237);
        result = prime * result + (localRequestOnly ? 1231 : 1237);
        result = prime * result + maxInsertRetries;
        result = prime * result + splitfileAlgo.code;
        result = prime * result + splitfileSegmentCheckBlocks;
        result = prime * result + splitfileSegmentDataBlocks;
        return result;
    }

    /** Are two InsertContext's equal? Ignores the EventProducer, compares only the actual config
     * values. */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        InsertContext other = (InsertContext) obj;
        if (canWriteClientCache != other.canWriteClientCache)
            return false;
        if (compatibilityMode != other.compatibilityMode)
            return false;
        if (compressorDescriptor == null) {
            if (other.compressorDescriptor != null)
                return false;
        } else if (!compressorDescriptor.equals(other.compressorDescriptor))
            return false;
        if (consecutiveRNFsCountAsSuccess != other.consecutiveRNFsCountAsSuccess)
            return false;
        if (dontCompress != other.dontCompress)
            return false;
        // eventProducer is ignored, and assumed to be unique.
        if (extraInsertsSingleBlock != other.extraInsertsSingleBlock)
            return false;
        if (extraInsertsSplitfileHeaderBlock != other.extraInsertsSplitfileHeaderBlock)
            return false;
        if (forkOnCacheable != other.forkOnCacheable)
            return false;
        if (ignoreUSKDatehints != other.ignoreUSKDatehints)
            return false;
        if (localRequestOnly != other.localRequestOnly)
            return false;
        if (maxInsertRetries != other.maxInsertRetries)
            return false;
        if (splitfileAlgo != other.splitfileAlgo)
            return false;
        if (splitfileSegmentCheckBlocks != other.splitfileSegmentCheckBlocks)
            return false;
        if (splitfileSegmentDataBlocks != other.splitfileSegmentDataBlocks)
            return false;
        return true;
    }
    
    public SplitfileAlgorithm getSplitfileAlgorithm() {
        return splitfileAlgo;
    }
    
    /** Call when migrating from db4o era. FIXME remove.
     * @deprecated */
    @Deprecated
    public void onResume() {
        // Used to encode it as a long.
        if(realCompatMode == null)
            realCompatMode = CompatibilityMode.byCode((short)compatibilityMode);
        // Max blocks was wrong too.
        splitfileSegmentDataBlocks = FECCodec.MAX_TOTAL_BLOCKS_PER_SEGMENT;
        splitfileSegmentCheckBlocks = FECCodec.MAX_TOTAL_BLOCKS_PER_SEGMENT;
        splitfileAlgo = SplitfileAlgorithm.getByCode(splitfileAlgorithm);
    }

}
