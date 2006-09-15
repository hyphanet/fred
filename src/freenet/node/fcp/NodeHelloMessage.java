/*
  NodeHelloMessage.java / Freenet
  Copyright (C) 2005-2006 The Free Network project

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

package freenet.node.fcp;

import freenet.node.Node;
import freenet.node.Version;
import freenet.support.Fields;
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
	public static final String name = "NodeHello";
	String nodeVersion;
	String nodeFCPVersion;
	String nodeNode;
	String nodeCompressionCodecs;
	boolean isTestnet;
	
	private Node node;
	
	public NodeHelloMessage(SimpleFieldSet fs) throws MessageInvalidException {	
		this.nodeNode = fs.get("Node");
		if(nodeNode == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No Node!", null);
		else if(!nodeNode.equals("Fred"))
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Not talking to Fred!", null);
		
		this.nodeFCPVersion = fs.get("FCPVersion");
		if(nodeFCPVersion == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No FCPVersion!", null);
		else if(!nodeFCPVersion.equals("2.0"))
			throw new MessageInvalidException(ProtocolErrorMessage.NOT_SUPPORTED, "FCPVersion is incompatible!", null);
		
		this.nodeVersion = fs.get("Version");
		if(nodeVersion == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No Version!", null);
		else if(!nodeVersion.startsWith("Fred,0.7,1.0,"))
			throw new MessageInvalidException(ProtocolErrorMessage.NOT_SUPPORTED, "Fred Version is incompatible!", null);
		
		this.nodeCompressionCodecs = fs.get("CompressionCodecs");
		if(nodeCompressionCodecs == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No CompressionCodecs!", null);	
		
		this.isTestnet = Fields.stringToBool(fs.get("Testnet"), false);
	}
	
	public NodeHelloMessage(final Node node) {
		this.node = node;
	}
	
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet();
		// FIXME
		sfs.put("FCPVersion", "2.0");
		sfs.put("Node", "Fred");
		sfs.put("Version", Version.getVersionString());
		sfs.put("Testnet", Boolean.toString(node == null ? false : node.isTestnetEnabled()));
		sfs.put("CompressionCodecs", Integer.toString(Compressor.countCompressAlgorithms()));
		return sfs;
	}

	public String getName() {
		return NodeHelloMessage.name;
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "NodeHello goes from server to client not the other way around", null);
	}

}
