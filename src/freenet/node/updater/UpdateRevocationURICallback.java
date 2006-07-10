package freenet.node.updater;

import freenet.config.StringCallback;
import freenet.node.Node;
import freenet.support.Logger;

public class UpdateRevocationURICallback implements StringCallback{

	private final Node node;
	private static final String baseURI = "SSK@VOfCZVTYPaatJ~eB~4lu2cPrWEmGyt4bfbB1v15Z6qQ,B6EynLhm7QE0se~rMgWWhl7wh3rFWjxJsEUcyohAm8A,AQABAAE/revoked/";
			
	public UpdateRevocationURICallback(Node node) {
		this.node = node;
	}
	
	public String get() {
		NodeUpdater nu = node.getNodeUpdater();
		if (nu != null)
			return nu.getRevocationKey().toString(true);
		else
			return baseURI;
	}

	public void set(String val) {
		if(val.equals(get())) return;
		// Good idea to prevent it ? 
		//
		// Maybe it NEEDS to be implemented
		Logger.error(this, "Node's updater revocationURI can't be updated on the fly");
	}	
}
