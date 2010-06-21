package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.client.InsertContext;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class CompatibilityMode extends FCPMessage {
	
	final String identifier;
	long min;
	long max;
	final boolean global;
	
	CompatibilityMode(String id, boolean global, long min, long max) {
		this.identifier = id;
		this.global = global;
		this.min = min;
		this.max = max;
	}
	
	void merge(long min, long max) {
		if(min > this.min) this.min = min;
		if(max < this.max || this.max < 0) this.max = max;
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

}
