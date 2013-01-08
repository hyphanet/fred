package freenet.node;

import java.io.IOException;
import java.util.HashMap;

import freenet.node.TransportManager.TransportMode;
import freenet.pluginmanager.TransportPluginConfigurationException;
import freenet.support.SimpleFieldSet;

/**
 * TransportManagerConfig keeps a track of all configurations and ports fred binds to.
 * For now its sole purpose is to track transport plugin bindings in the transportConfigMap field.
 * @author chetan
 *
 */
public class TransportManagerConfig {
	
	public final TransportMode transportMode;
	
	private HashMap<String, SimpleFieldSet> transportConfigMap = new HashMap<String, SimpleFieldSet> ();
	
	public HashMap<String, Boolean> enabledTransports = new HashMap<String, Boolean> ();
	
	public TransportManagerConfig(TransportMode transportMode) {
		this.transportMode = transportMode;
	}
	
	public synchronized void addTransportConfig(String transportName, SimpleFieldSet transportConfig) {
		transportConfigMap.put(transportName, transportConfig);
	}
	
	public synchronized SimpleFieldSet getTransportConfig(String transportName) throws TransportPluginConfigurationException {
		if(!transportConfigMap.containsKey(transportName))
			throw new TransportPluginConfigurationException(transportName + " not configured.");
		return transportConfigMap.get(transportName);
	}
	
	public void writeConfigData() throws IOException {
		
	}

}
