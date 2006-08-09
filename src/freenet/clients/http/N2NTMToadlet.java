package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.UdpSocketManager;
import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.support.HTMLEncoder;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.io.Bucket;

public class N2NTMToadlet extends Toadlet {

  private Node node;
  private UdpSocketManager usm;
  
  protected N2NTMToadlet(Node n, HighLevelSimpleClient client) {
    super(client);
    this.node = n;
    this.usm = n.getUSM();
  }

  public String supportedMethods() {
    return "GET, POST";
  }

  public void handleGet(URI uri, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
	  
	  HTTPRequest request = new HTTPRequest(uri, null, ctx);
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
		  
		  HTMLNode infobox = contentNode.addChild("div", new String[] { "class", "id" }, new String[] { "infobox", "n2nbox" });
		  infobox.addChild("div", "class", "infobox-header", "Send Node to Node Text Message");
		  HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
		  HTMLNode messageForm = infoboxContent.addChild("form", new String[] { "action", "method", "enctype" }, new String[] { ".", "post", "multipart/form-data" });
		  messageForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", node.formPassword });
		  messageForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "hashcode", input_hashcode_string });
		  messageForm.addChild("textarea", new String[] { "id", "name", "rows", "cols" }, new String[] { "n2ntmtext", "message", "8", "74" });
		  messageForm.addChild("br");
		  messageForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "send", "Send message to " + peernode_name });
		  StringBuffer pageBuffer = new StringBuffer();
		  pageNode.generate(pageBuffer);
		  this.writeReply(ctx, 200, "text/html", "OK", pageBuffer.toString());
		  return;
	  }
	  MultiValueTable headers = new MultiValueTable();
	  headers.put("Location", "/darknet/");
	  ctx.sendReplyHeaders(302, "Found", headers, null, 0);
  }
  
  private HTMLNode createPeerInfobox(String infoboxType, String header, String message) {
	  HTMLNode infobox = new HTMLNode("div", "class", "infobox " + infoboxType);
	  infobox.addChild("div", "class", "infobox-header", header);
	  HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
	  infoboxContent.addChild("#", message);
	  HTMLNode list = infoboxContent.addChild("ul");
	  list.addChild("li").addChild("a", new String[] { "href", "title" }, new String[] { "/", "Back to node homepage" }, "Homepage");
	  list.addChild("li").addChild("a", new String[] { "href", "title" }, new String[] { "/darknet/", "Back to darknet connections" }, "Darknet connections");
	  return infobox;
  }
  
  public void handlePost(URI uri, Bucket data, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
	  if(data.size() > 1024*1024) {
		  this.writeReply(ctx, 400, "text/plain", "Too big", "Too much data, N2NTM toadlet limited to 1MB");
		  return;
	  }
	  
	  HTTPRequest request = new HTTPRequest(uri, data, ctx);
	  
	  String pass = request.getPartAsString("formPassword", 32);
	  if((pass == null) || !pass.equals(node.formPassword)) {
		  MultiValueTable headers = new MultiValueTable();
		  headers.put("Location", "/send_n2ntm/");
		  ctx.sendReplyHeaders(302, "Found", headers, null, 0);
		  return;
	  }
	  
	  if (request.isPartSet("send")) {
		  String message = request.getPartAsString("message", 2000);
		  message = message.trim();
		  PeerNode pn = null;
		  String input_hashcode_string = request.getPartAsString("hashcode", 2000);
		  request.freeParts();
		  input_hashcode_string = input_hashcode_string.trim();
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
					  pn = peerNodes[i];
					  break;
				  }
			  }
		  }
		  if(pn == null) {
			  HTMLNode pageNode = ctx.getPageMaker().getPageNode("Node to Node Text Message failed");
			  HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			  contentNode.addChild(createPeerInfobox("infobox-error", "Peer not found", "The peer with the hash code \u201c" + input_hashcode_string + "\u201d could not be found."));
			  StringBuffer pageBuffer = new StringBuffer();
			  pageNode.generate(pageBuffer);
			  this.writeReply(ctx, 200, "text/html", "OK", pageBuffer.toString());
			  return;
		  }
		  HTMLNode pageNode = null;
		  try {
			  Message n2ntm = DMT.createNodeToNodeTextMessage(Node.N2N_TEXT_MESSAGE_TYPE_USERALERT, node.getMyName(), pn.getName(), message);
			  if(pn == null) {
				  pageNode = ctx.getPageMaker().getPageNode("Node to Node Text Message failed");
				  HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
				  contentNode.addChild(createPeerInfobox("infobox-error", "Peer not found", "The peer with the hash code \u201c" + request.getParam("hashcode") + "\u201d could not be found."));
			  } else if(!pn.isConnected()) {
				  pageNode = ctx.getPageMaker().getPageNode("Node to Node Text Message failed");
				  HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
				  contentNode.addChild(createPeerInfobox("infobox-error", "Peer not connected", "The peer \u201c" + pn.getName() + " is not connected."));
			  } else if(pn.getPeerNodeStatus() == Node.PEER_NODE_STATUS_ROUTING_BACKED_OFF) {
				  pageNode = ctx.getPageMaker().getPageNode("Node to Node Text Message succeeded");
				  HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
				  contentNode.addChild(createPeerInfobox("infobox-warning", "Node to Node Text Message sent", "The message was successfully sent to \u201c" + pn.getName() + ",\u201d but the node is backed off, so receipt may be significantly delayed."));
				  usm.send(pn, n2ntm, null);
				  Logger.normal(this, "Sent N2NTM to '"+pn.getName()+"': "+message);
			  } else {
				  pageNode = ctx.getPageMaker().getPageNode("Node to Node Text Message succeeded");
				  HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
				  contentNode.addChild(createPeerInfobox("infobox-success", "Node to Node Text Message sent", "The message was successfully sent to \u201c" + pn.getName() + ".\u201d"));
				  usm.send(pn, n2ntm, null);
				  Logger.normal(this, "Sent N2NTM to '"+pn.getName()+"': "+message);
			  }
		  } catch (NotConnectedException e) {
			  pageNode = ctx.getPageMaker().getPageNode("Node to Node Text Message failed");
			  HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			  contentNode.addChild(createPeerInfobox("infobox-error", "Peer not connected", "Could not send the Node to Node Text Message to \u201c" + pn.getName() + ".\u201d"));
			  Logger.error(this, "Caught NotConnectedException while trying to send n2ntm: "+e);
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
}
