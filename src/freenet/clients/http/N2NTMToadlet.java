/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.IOException;
import java.net.URI;
import java.util.Iterator;
import java.util.HashMap;

import freenet.client.HighLevelSimpleClient;
import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.UdpSocketManager;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.PeerNode;
import freenet.node.Version;
import freenet.support.Base64;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.api.HTTPRequest;

public class N2NTMToadlet extends Toadlet {

  private Node node;
  private NodeClientCore core;
  private UdpSocketManager usm;
  
  protected N2NTMToadlet(Node n, NodeClientCore core, HighLevelSimpleClient client) {
    super(client);
    this.node = n;
    this.core = core;
    this.usm = n.getUSM();
  }

  public String supportedMethods() {
    return "GET, POST";
  }

  public void handleGet(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
	  if (request.isParameterSet("peernode_hashcode")) {
		  HTMLNode pageNode = ctx.getPageMaker().getPageNode("Send Node to Node Text Message");
		  HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
		  
		  String peernode_name = null;
		  String input_hashcode_string = request.getParam("peernode_hashcode");
		  int input_hashcode = -1;
		  try {
			  input_hashcode = (new Integer(input_hashcode_string)).intValue();
		  } catch (NumberFormatException e) {
			  // ignore here, handle below
		  }
		  if(input_hashcode != -1) {
			  PeerNode[] peerNodes = node.getDarknetConnections();
			  for(int i = 0; i < peerNodes.length; i++) {
				  int peer_hashcode = peerNodes[i].hashCode();
				  if(peer_hashcode == input_hashcode) {
					  peernode_name = peerNodes[i].getName();
					  break;
				  }
			  }
		  }
		  if(peernode_name == null) {
			  contentNode.addChild(createPeerInfobox("infobox-error", "Peer not found", "The peer with the hash code \u201c" + input_hashcode_string + "\u201d could not be found."));
			  StringBuffer pageBuffer = new StringBuffer();
			  pageNode.generate(pageBuffer);
			  this.writeReply(ctx, 200, "text/html", "OK", pageBuffer.toString());
			  return;
		  }
			HashMap peers = new HashMap();
			peers.put( input_hashcode_string, peernode_name );
			String resultString = createN2NTMSendForm( pageNode, contentNode, ctx, peers);
			if(resultString != null) {  // was there an error in createN2NTMSendForm()?
				this.writeReply(ctx, 200, "text/html", "OK", resultString);
				return;
			}
		  StringBuffer pageBuffer = new StringBuffer();
		  pageNode.generate(pageBuffer);
		  this.writeReply(ctx, 200, "text/html", "OK", pageBuffer.toString());
		  return;
	  }
	  MultiValueTable headers = new MultiValueTable();
	  headers.put("Location", "/darknet/");
	  ctx.sendReplyHeaders(302, "Found", headers, null, 0);
  }
  
  private static HTMLNode createPeerInfobox(String infoboxType, String header, String message) {
	  HTMLNode infobox = new HTMLNode("div", "class", "infobox " + infoboxType);
	  infobox.addChild("div", "class", "infobox-header", header);
	  HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
	  infoboxContent.addChild("#", message);
	  HTMLNode list = infoboxContent.addChild("ul");
	  list.addChild("li").addChild("a", new String[] { "href", "title" }, new String[] { "/", "Back to node homepage" }, "Homepage");
	  list.addChild("li").addChild("a", new String[] { "href", "title" }, new String[] { "/darknet/", "Back to darknet connections" }, "Darknet connections");
	  return infobox;
  }
  
  public void handlePost(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
	  String pass = request.getPartAsString("formPassword", 32);
	  if((pass == null) || !pass.equals(core.formPassword)) {
		  MultiValueTable headers = new MultiValueTable();
		  headers.put("Location", "/send_n2ntm/");
		  ctx.sendReplyHeaders(302, "Found", headers, null, 0);
		  return;
	  }
	  
	  if (request.isPartSet("send")) {
		  String message = request.getPartAsString("message", 5*1024);
		  message = message.trim();
			if(message.length() > 1024) {
				this.writeReply(ctx, 400, "text/plain", "Too long", "N2NTMs are limited to 1024 characters");
				return;
			}
			HTMLNode pageNode = ctx.getPageMaker().getPageNode("Send Node to Node Text Message Processing");
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			HTMLNode peerTableInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode peerTable = peerTableInfobox.addChild("table", "class", "n2ntm-send-statuses");
			HTMLNode peerTableHeaderRow = peerTable.addChild("tr");
			peerTableHeaderRow.addChild("th", "Peer Name");
			peerTableHeaderRow.addChild("th", "N2NTM Send Status");
			PeerNode[] peerNodes = node.getDarknetConnections();
			for(int i = 0; i < peerNodes.length; i++) {
				if (request.isPartSet("node_"+peerNodes[i].hashCode())) {
					PeerNode pn = peerNodes[i];
					String sendStatusShort;
					String sendStatusLong;
					String sendStatusClass;
					try {
						long now = System.currentTimeMillis();
						SimpleFieldSet fs = new SimpleFieldSet();
						fs.put("type", Integer.toString(Node.N2N_TEXT_MESSAGE_TYPE_USERALERT));
						fs.put("source_nodename", Base64.encode(node.getMyName().getBytes()));
						fs.put("target_nodename", Base64.encode(pn.getName().getBytes()));
						fs.put("text", Base64.encode(message.getBytes()));
						fs.put("composedTime", Long.toString(now));
						fs.put("sentTime", Long.toString(now));
						Message n2ntm;
						if(Version.buildNumber() < 1000) {  // FIXME/TODO: This test shouldn't be needed eventually
							n2ntm = DMT.createNodeToNodeTextMessage(Node.N2N_TEXT_MESSAGE_TYPE_USERALERT, node.getMyName(), pn.getName(), message);
						} else {
							n2ntm = DMT.createNodeToNodeMessage(Node.N2N_TEXT_MESSAGE_TYPE_USERALERT, fs.toString().getBytes("UTF-8"));
						}
						if(!pn.isConnected()) {
							sendStatusShort = "Queued";
							sendStatusLong = "Queued: Peer not connected, so message queued for when it connects";
							sendStatusClass = "n2ntm-send-queued";
							fs.removeValue("sentTime");
							pn.queueN2NTM(fs);
							Logger.normal(this, "Queued N2NTM to '"+pn.getName()+"': "+message);
						} else if(pn.getPeerNodeStatus() == Node.PEER_NODE_STATUS_ROUTING_BACKED_OFF) {
							sendStatusShort = "Delayed";
							sendStatusLong = "Backed off: Sending of message possibly delayed to peer";
							sendStatusClass = "n2ntm-send-delayed";
							usm.send(pn, n2ntm, null);
							Logger.normal(this, "Sent N2NTM to '"+pn.getName()+"': "+message);
						} else {
							sendStatusShort = "Sent";
							sendStatusLong = "Message sent to peer";
							sendStatusClass = "n2ntm-send-sent";
							usm.send(pn, n2ntm, null);
							Logger.normal(this, "Sent N2NTM to '"+pn.getName()+"': "+message);
						}
					} catch (NotConnectedException e) {
						sendStatusShort = "Failed";
						sendStatusLong = "Message not sent to peer: peer not connected";
						sendStatusClass = "n2ntm-send-failed";
					}
					HTMLNode peerRow = peerTable.addChild("tr");
					peerRow.addChild("td", "class", "peer-name").addChild("#", pn.getName());
					peerRow.addChild("td", "class", sendStatusClass).addChild("span", new String[] { "title", "style" }, new String[] { sendStatusLong, "border-bottom: 1px dotted; cursor: help;" }, sendStatusShort);
				}
			}
			HTMLNode infoboxContent = peerTableInfobox.addChild("div", "class", "n2ntm-message-text");
			infoboxContent.addChild("#", message);
			HTMLNode list = peerTableInfobox.addChild("ul");
			list.addChild("li").addChild("a", new String[] { "href", "title" }, new String[] { "/", "Back to node homepage" }, "Homepage");
			list.addChild("li").addChild("a", new String[] { "href", "title" }, new String[] { "/darknet/", "Back to darknet connections" }, "Darknet connections");
		  StringBuffer pageBuffer = new StringBuffer();
		  pageNode.generate(pageBuffer);
		  this.writeReply(ctx, 200, "text/html", "OK", pageBuffer.toString());
		  return;
	  }
	  MultiValueTable headers = new MultiValueTable();
	  headers.put("Location", "/darknet/");
	  ctx.sendReplyHeaders(302, "Found", headers, null, 0);
	}
	    
	public static String createN2NTMSendForm(HTMLNode pageNode, HTMLNode contentNode, ToadletContext ctx, HashMap peers) throws ToadletContextClosedException, IOException {
		if(contentNode == null) {
			contentNode.addChild(createPeerInfobox("infobox-error", "Internal error", "Internal error: N2NTMToadlet.createN2NTMSendForm() not passed a valid contentNode."));
			StringBuffer pageBuffer = new StringBuffer();
			pageNode.generate(pageBuffer);
			return pageBuffer.toString();
		}
		HTMLNode infobox = contentNode.addChild("div", new String[] { "class", "id" }, new String[] { "infobox", "n2nbox" });
		infobox.addChild("div", "class", "infobox-header", "Send Node to Node Text Message");
		HTMLNode messageTargets = infobox.addChild("div", "class", "infobox-content");
		messageTargets.addChild("p", "Composing N2NTM to send to the following peers:");
		HTMLNode messageTargetList = messageTargets.addChild("ul");
		// Iterate peers
		for (Iterator it = peers.values().iterator(); it.hasNext(); ) {
			String peer_name = (String) it.next();
			messageTargetList.addChild("li", peer_name);
		}
		HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
		HTMLNode messageForm = ctx.addFormChild(infoboxContent, "/send_n2ntm/", "sendN2NTMForm");
		// Iterate peers
		for (Iterator it = peers.keySet().iterator(); it.hasNext(); ) {
			String peerNodeHash = (String) it.next();
			messageForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "node_"+peerNodeHash, "1" });
		}
		messageForm.addChild("textarea", new String[] { "id", "name", "rows", "cols" }, new String[] { "n2ntmtext", "message", "8", "74" });
		messageForm.addChild("br");
		messageForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "send", "Send message" });
		return null;
	}
}
