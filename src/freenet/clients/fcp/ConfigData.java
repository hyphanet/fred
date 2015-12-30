/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import freenet.config.Config;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class ConfigData extends FCPMessage {
	static final String name = "ConfigData";
	
	final Node node;
	final boolean withCurrent;
	final boolean withDefaults;
	final boolean withSortOrder;
	final boolean withExpertFlag;
	final boolean withForceWriteFlag;
	final boolean withShortDescription;
	final boolean withLongDescription;
	final boolean withDataTypes;
	final String identifier;
	
	public ConfigData(Node node, boolean withCurrent, boolean withDefaults, boolean withSortOrder, boolean withExpertFlag, boolean withForceWriteFlag, boolean withShortDescription, boolean withLongDescription, boolean withDataTypes, String identifier) {
		this.node = node;
		this.withCurrent = withCurrent;
		this.withDefaults = withDefaults;
		this.withSortOrder = withSortOrder;
		this.withExpertFlag = withExpertFlag;
		this.withForceWriteFlag = withForceWriteFlag;
		this.withShortDescription = withShortDescription;
		this.withLongDescription = withLongDescription;
		this.withDataTypes = withDataTypes;
		this.identifier = identifier;
	}

	
	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		if(withCurrent) {
			SimpleFieldSet current = node.config.exportFieldSet(Config.RequestType.CURRENT_SETTINGS, true);
			if(!current.isEmpty()) {
				fs.put("current", current);
			}
		}
		if(withDefaults) {
			SimpleFieldSet defaultSettings = node.config.exportFieldSet(Config.RequestType.DEFAULT_SETTINGS, false);
			if(!defaultSettings.isEmpty()) {
				fs.put("default", defaultSettings);
			}
		}
		if(withSortOrder) {
			SimpleFieldSet sortOrder = node.config.exportFieldSet(Config.RequestType.SORT_ORDER, false);
			if(!sortOrder.isEmpty()) {
				fs.put("sortOrder", sortOrder);
			}
		}
		if(withExpertFlag) {
			SimpleFieldSet expertFlag = node.config.exportFieldSet(Config.RequestType.EXPERT_FLAG, false);
			if(!expertFlag.isEmpty()) {
				fs.put("expertFlag", expertFlag);
			}
		}
		if(withForceWriteFlag) {
			SimpleFieldSet forceWriteFlag = node.config.exportFieldSet(Config.RequestType.FORCE_WRITE_FLAG, false);
			if(!forceWriteFlag.isEmpty()) {
				fs.put("forceWriteFlag", forceWriteFlag);
			}
		}
		if(withShortDescription) {
			SimpleFieldSet shortDescription = node.config.exportFieldSet(Config.RequestType.SHORT_DESCRIPTION, false);
			if(!shortDescription.isEmpty()) {
				fs.put("shortDescription", shortDescription);
			}
		}
		if(withLongDescription) {
			SimpleFieldSet longDescription = node.config.exportFieldSet(Config.RequestType.LONG_DESCRIPTION, false);
			if(!longDescription.isEmpty()) {
				fs.put("longDescription", longDescription);
			}
		}
		if(withDataTypes) {
			SimpleFieldSet type = node.config.exportFieldSet(Config.RequestType.DATA_TYPE, false);
			if(!type.isEmpty()) {
				fs.put("dataType", type);
			}
		}
		if(identifier != null)
			fs.putSingle("Identifier", identifier);
		return fs;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "ConfigData goes from server to client not the other way around", null, false);
	}

}
