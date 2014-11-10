package freenet.clients.fcp;

import freenet.client.InsertContext;
import freenet.client.async.CompatibilityAnalyser;
import freenet.node.Node;
import freenet.support.HexUtil;
import freenet.support.SimpleFieldSet;

public class CompatibilityMode extends FCPMessage {
    
    public CompatibilityMode(String identifier, boolean global, CompatibilityAnalyser compat) {
        this.identifier = identifier;
        this.global = global;
        this.compat = compat;
    }
	
    private static final long serialVersionUID = 1L;
    
    private final CompatibilityAnalyser compat;
    final String identifier;
    final boolean global;
    
	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(false);
		fs.putOverwrite("Min", compat.min().name());
		fs.putOverwrite("Max", compat.max().name());
		fs.put("Min.Number", compat.min().ordinal());
		fs.put("Max.Number", compat.max().ordinal());
		fs.putOverwrite("Identifier", identifier);
		fs.put("Global", global);
		byte[] cryptoKey = compat.getCryptoKey();
		if(cryptoKey != null)
			fs.putOverwrite("SplitfileCryptoKey", HexUtil.bytesToHex(cryptoKey));
		fs.put("DontCompress", compat.dontCompress());
		fs.put("Definitive", compat.definitive());
		return fs;
	}
	
	@Override
	public String getName() {
		return "CompatibilityMode";
	}
	
	@Override
	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new UnsupportedOperationException();
	}

	public InsertContext.CompatibilityMode[] getModes() {
	    return compat.getModes();
	}

}
