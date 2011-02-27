/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.node.NodeStarter;
import freenet.node.Version;
import freenet.support.SimpleFieldSet;
import freenet.support.compress.Compressor;

/**
 * NodeHello
 *
 * NodeHello
 * FCPVersion=<protocol version>
 * Node=Fred
 * Version=0.7.0,401
 * EndMessage
 */
public class NodeHelloMessage extends FCPMessage {
	public static final String NAME = "NodeHello";
	
	private final Node node;
	private final String id;
		
	public NodeHelloMessage(final Node node, String id) {
		this.node = node;
		this.id = id;
	}
	
	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet(true);
		// FIXME
		sfs.putSingle("FCPVersion", "2.0");
		sfs.putSingle("Node", "Fred");
		sfs.putSingle("Version", Version.getVersionString());
		sfs.put("Build", Version.buildNumber());
		sfs.putSingle("Revision", Version.cvsRevision());
		sfs.put("ExtBuild", NodeStarter.extBuildNumber);
		sfs.putSingle("ExtRevision", NodeStarter.extRevisionNumber);
		sfs.put("Testnet", node.isTestnetEnabled());
		sfs.putSingle("CompressionCodecs", Compressor.COMPRESSOR_TYPE.getHelloCompressorDescriptor());
		sfs.putSingle("ConnectionIdentifier", id);
		sfs.putSingle("NodeLanguage", NodeL10n.getBase().getSelectedLanguage().toString());
		return sfs;
	}

	@Override
	public String getName() {
		return NodeHelloMessage.NAME;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "NodeHello goes from server to client not the other way around", null, false);
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		container.delete(this);
	}

}
