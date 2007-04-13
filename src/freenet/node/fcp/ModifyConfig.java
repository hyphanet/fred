/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.config.Config;
import freenet.config.Option;
import freenet.config.SubConfig;
import freenet.node.Node;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

public class ModifyConfig extends FCPMessage {

	static final String NAME = "ModifyConfig";
	
	final SimpleFieldSet fs;
	
	public ModifyConfig(SimpleFieldSet fs) {
		this.fs = fs;
	}

	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet(true);
	}

	public String getName() {
		return NAME;
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		if(!handler.hasFullAccess()) {
			throw new MessageInvalidException(ProtocolErrorMessage.ACCESS_DENIED, "ModifyConfig requires full access", fs.get("Identifier"), false);
		}
		Config config = node.config;
		SubConfig[] sc = config.getConfigs();
		
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		
		for(int i=0; i<sc.length ; i++){
			Option[] o = sc[i].getOptions();
			String prefix = sc[i].getPrefix();
			String configName;
			
			for(int j=0; j<o.length; j++){
				configName=o[j].getName();
				if(logMINOR) Logger.minor(this, "Setting "+prefix+ '.' +configName);
				
				// we ignore unreconized parameters 
				if(fs.get(prefix+ '.' +configName) != null) {
					if(!(o[j].getValueString().equals(fs.get(prefix+ '.' +configName)))){
						if(logMINOR) Logger.minor(this, "Setting "+prefix+ '.' +configName+" to "+fs.get(prefix+ '.' +configName));
						try{
							o[j].setValue(fs.get(prefix+ '.' +configName));
						}catch(Exception e){
							// Bad values silently fail from an FCP perspective, but the FCP client can tell if a change took by comparing ConfigData messages before and after
							Logger.error(this, "Caught "+e, e);
						}
					}
				}
			}
		}
		node.clientCore.storeConfig();
		handler.outputHandler.queue(new ConfigData(node, true, false, false, false, false, false, false));
	}
}
