/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.node.Node;
import freenet.support.Fields;
import freenet.support.SimpleFieldSet;

public class GetConfig extends FCPMessage {

	final boolean withCurrent;
	final boolean withDefaults;
	final boolean withSortOrder;
	final boolean withExpertFlag;
	final boolean withForceWriteFlag;
	final boolean withShortDescription;
	final boolean withLongDescription;
	final boolean withDataTypes;
	static final String NAME = "GetConfig";
	final String identifier;
	
	public GetConfig(SimpleFieldSet fs) {
		withCurrent = Fields.stringToBool(fs.get("WithCurrent"), false);
		withDefaults = Fields.stringToBool(fs.get("WithDefaults"), false);
		withSortOrder = Fields.stringToBool(fs.get("WithSortOrder"), false);
		withExpertFlag = Fields.stringToBool(fs.get("WithExpertFlag"), false);
		withForceWriteFlag = Fields.stringToBool(fs.get("WithForceWriteFlag"), false);
		withShortDescription = Fields.stringToBool(fs.get("WithShortDescription"), false);
		withLongDescription = Fields.stringToBool(fs.get("WithLongDescription"), false);
		withDataTypes = Fields.stringToBool(fs.get("WithDataTypes"), false);
		this.identifier = fs.get("Identifier");
		fs.removeValue("Identifier");
	}
	
	@Override
	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet(true);
	}
	
	@Override
	public String getName() {
		return NAME;
	}
	
	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		if(!handler.hasFullAccess()) {
			throw new MessageInvalidException(ProtocolErrorMessage.ACCESS_DENIED, "GetConfig requires full access", identifier, false);
		}
		handler.outputHandler.queue(new ConfigData(node, withCurrent, withDefaults, withSortOrder, withExpertFlag, withForceWriteFlag, withShortDescription, withLongDescription, withDataTypes, identifier));
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		container.delete(this);
	}
	
}
