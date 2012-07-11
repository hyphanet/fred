package freenet.pluginmanager;

import java.io.IOException;

import freenet.support.SimpleFieldSet;

public interface TransportConfig {
	
	public SimpleFieldSet getConfig();
	
	public void writeConfig(SimpleFieldSet config) throws IOException;

}
