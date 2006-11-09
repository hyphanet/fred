/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.node.Node;
import freenet.support.Fields;
import freenet.support.SimpleFieldSet;

public class GetConfig extends FCPMessage {

	final boolean withDefaults;
	static final String name = "GetConfig";
	
	public GetConfig(SimpleFieldSet fs) {
		withDefaults = Fields.stringToBool(fs.get("WithDefaults"), false);
	}
	
	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet();
	}
	
	public String getName() {
		return name;
	}
	
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		handler.outputHandler.queue(new ConfigData(node, true));
	}
	
}
