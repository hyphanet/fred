/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.node.Node;
import freenet.pluginmanager.PluginInfoWrapper;
import freenet.support.SimpleFieldSet;

/**
 * @author saces
 *
 */
public class PluginInfoMessage extends FCPMessage {
	
	static final String NAME = "PluginInfo";
	
	private final String identifier;
	
	private final boolean detailed;

	private final String classname;
	private final String originuri;
	private final long started;

	
	PluginInfoMessage(PluginInfoWrapper pi, String identifier, boolean detail) {
		this.identifier = identifier;
		this.detailed = detail;
		classname = pi.getPluginClassName();
		originuri = pi.getFilename();
		started = pi.getStarted();
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet(true);
		if(identifier != null) // is optional on these two only
			sfs.putSingle("Identifier", identifier);
		sfs.putSingle("PluginName", classname);
		
		if (detailed) {
			sfs.putSingle("OriginUri", originuri);
			sfs.put("Started", started);
			//sfs.putSingle("TempFilename", tempfilename);
		}
		return sfs;
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, NAME + " goes from server to client not the other way around", null, false);
	}

}
