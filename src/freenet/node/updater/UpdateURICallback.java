package freenet.node.updater;

import freenet.config.StringCallback;
import freenet.node.Node;
import freenet.node.Version;
import freenet.support.Logger;

public class UpdateURICallback implements StringCallback{

	private final Node node;
	private static final String baseURI = "freenet:USK@SIDKS6l-eOU8IQqDo03d~3qqBd-69WG60aDgg4nWqss,CPFqYi95Is3GwzAdAKtAuFMCXDZFFWC3~uPoidCD67s,AQABAAE/update/";
			
	public UpdateURICallback(Node node) {
		this.node = node;
	}
	
	public String get() {
		NodeUpdater nu = node.getNodeUpdater();
		if (nu != null)
			return nu.getUpdateKey().toString(true);
		else
			return baseURI+Version.buildNumber()+"/";
	}

	public void set(String val) {
		if(val!=null && val.equals(get())) return;
		// Good idea to prevent it ? 
		//
		// Maybe it NEEDS to be implemented
		Logger.error(this, "Node's updater URI can't be updated on the fly");
	}	
}