package freenet.node.fcp;

import java.util.Arrays;

import com.db4o.ObjectContainer;

import freenet.client.InsertContext;
import freenet.node.Node;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

public class CompatibilityMode extends FCPMessage {
	
	final String identifier;
	long min;
	long max;
	final boolean global;
	byte[] cryptoKey;
	boolean compressed;
	boolean bottomLayer;
	
	CompatibilityMode(String id, boolean global, long min, long max, byte[] cryptoKey, boolean compressed, boolean bottomLayer, boolean definitiveAnyway) {
		this.identifier = id;
		this.global = global;
		this.min = min;
		this.max = max;
		this.cryptoKey = cryptoKey;
		this.compressed = compressed;
		this.bottomLayer = bottomLayer || definitiveAnyway;
	}
	
	void merge(long min, long max, byte[] cryptoKey, boolean compressed, boolean bottomLayer, boolean definitiveAnyway) {
		if(bottomLayer || definitiveAnyway) bottomLayer = true;
		if(compressed) this.compressed = true;
		if(min > this.min && !bottomLayer) this.min = min;
		if((!bottomLayer) && max < this.max || this.max == InsertContext.CompatibilityMode.COMPAT_UNKNOWN.ordinal()) this.max = max;
		if(this.cryptoKey == null && !bottomLayer) {
			this.cryptoKey = cryptoKey;
		} else if(this.cryptoKey == null && cryptoKey != null && bottomLayer) {
			Logger.error(this, "Setting crypto key after bottom/definitive layer!");
		} else if(!Arrays.equals(this.cryptoKey, cryptoKey)) {
			Logger.error(this, "Two different crypto keys!");
			this.cryptoKey = null;
		}
	}
	
	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(false);
		fs.putOverwrite("Min", InsertContext.CompatibilityMode.values()[(int)min].name());
		fs.putOverwrite("Max", InsertContext.CompatibilityMode.values()[(int)max].name());
		fs.put("Min.Number", min);
		fs.put("Max.Number", max);
		fs.putOverwrite("Identifier", identifier);
		fs.put("Global", global);
		if(cryptoKey != null)
			fs.putOverwrite("SplitfileCryptoKey", HexUtil.bytesToHex(cryptoKey));
		fs.put("DontCompress", !compressed);
		fs.put("BottomLayer", bottomLayer);
		return fs;
	}
	
	@Override
	public String getName() {
		return "CompatibilityMode";
	}
	
	@Override
	public void removeFrom(ObjectContainer container) {
		container.delete(this);
	}
	
	@Override
	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new UnsupportedOperationException();
	}

	public InsertContext.CompatibilityMode[] getModes() {
		return new InsertContext.CompatibilityMode[] {
				InsertContext.CompatibilityMode.values()[(int)min],
				InsertContext.CompatibilityMode.values()[(int)max]
		};
	}

}
