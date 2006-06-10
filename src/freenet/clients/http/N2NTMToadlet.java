package freenet.clients.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;

import freenet.client.HighLevelSimpleClient;
import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.UdpSocketManager;
import freenet.node.FSParseException;
import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.node.Version;
import freenet.support.Bucket;
import freenet.support.HTMLEncoder;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.SimpleFieldSet;

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
    StringBuffer buf = new StringBuffer(1024);
    if (request.isParameterSet("peernode_hashcode")) {
      ctx.getPageMaker().makeHead(buf, "Send Node To Node Text Message");
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
        buf.append("PeerNode.hashCode '"+input_hashcode_string+"' not found.<br /><br />\n");
        buf.append("<a href=\"/darknet/\">Back to Darknet page</a>\n");
        ctx.getPageMaker().makeTail(buf);
        this.writeReply(ctx, 200, "text/html", "OK", buf.toString());
        return;
      }
      
      buf.append("<form action=\".\" method=\"post\" enctype=\"multipart/form-data\">\n");
      buf.append("<input type=\"hidden\" name=\"formPassword\" value=\""+node.formPassword+"\">");
      buf.append("<div class=\"infobox infobox-normal\">");
      buf.append("<div class=\"infobox-header\">");
      buf.append("Sending Node To Node Text Message to "+HTMLEncoder.encode(peernode_name)+"\n");
      buf.append("</div>");
      buf.append("<div class=\"infobox-content\" id=\"n2nbox\">");
      buf.append("<input type=\"hidden\" name=\"hashcode\" value=\""+input_hashcode_string+"\" />\n");
      buf.append("<textarea id=\"n2ntmtext\" name=\"message\" rows=\"8\" cols=\"74\"></textarea><br />\n");
      buf.append("<input type=\"submit\" name=\"send\" value=\"Send message to "+HTMLEncoder.encode(peernode_name)+"\" />\n");
      buf.append("</div>");
      buf.append("</div>");
      buf.append("</form>\n");
      ctx.getPageMaker().makeTail(buf);
      this.writeReply(ctx, 200, "text/html", "OK", buf.toString());
      return;
    }
    MultiValueTable headers = new MultiValueTable();
    headers.put("Location", "/darknet/");
    ctx.sendReplyHeaders(302, "Found", headers, null, 0);
  }
  
  public void handlePost(URI uri, Bucket data, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
    if(data.size() > 1024*1024) {
      this.writeReply(ctx, 400, "text/plain", "Too big", "Too much data, N2NTM toadlet limited to 1MB");
      return;
    }
    
    HTTPRequest request = new HTTPRequest(uri, data, ctx);
    StringBuffer buf = new StringBuffer(1024);
    
	String pass = request.getPartAsString("formPassword", 32);
	if(pass == null || !pass.equals(node.formPassword)) {
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
        ctx.getPageMaker().makeHead(buf, "Node To Node Text Message Failed");
	buf.append("<div class=\"infobox infobox-error\">");
	buf.append("<div class=\"infobox-header\">");
	buf.append("Peer not Found");
	buf.append("</div>");
	buf.append("<div class=\"infobox-content\">");
        buf.append("PeerNode.hashCode '"+input_hashcode_string+"' not found.<br /><br />\n");
	buf.append("</div>");
	buf.append("</div>");
        buf.append("<a href=\"/darknet/\">Back to Darknet page</a>\n");
        ctx.getPageMaker().makeTail(buf);
        this.writeReply(ctx, 200, "text/html", "OK", buf.toString());
        return;
      }
      try {
        Message n2ntm = DMT.createNodeToNodeTextMessage(Node.N2N_TEXT_MESSAGE_TYPE_USERALERT, node.getMyName(), pn.getName(), message);
        String messageTextBuf = HTMLEncoder.encode(message);
        int j = messageTextBuf.length();
              StringBuffer messageTextBuf2 = new StringBuffer(j);
              for (int i = 0; i < j; i++) {
                char ch = messageTextBuf.charAt(i);
                if(ch == '\n')
                  messageTextBuf2.append("<br />");
                else
                  messageTextBuf2.append(ch);
              }
        if(pn == null) {
          ctx.getPageMaker().makeHead(buf, "Node To Node Text Message Failed");
	  
	  buf.append("<div class=\"infobox infobox-error\">");
	  buf.append("<div class=\"infobox-header\">");
	  buf.append("Peer not Found");
	  buf.append("</div>");
	  buf.append("<div class=\"infobox-content\">");
	  buf.append("PeerNode.hashCode '"+request.getParam("hashcode")+"' not found.<br /><br />\n");
	  buf.append("</div>");
	  buf.append("</div>");
        } else if(!pn.isConnected()) {
          ctx.getPageMaker().makeHead(buf, "Node To Node Text Message Failed");
	  
	  buf.append("<div class=\"infobox infobox-error\">");
	  buf.append("<div class=\"infobox-header\">");
	  buf.append("Peer not Connected");
	  buf.append("</div>");
	  buf.append("<div class=\"infobox-content\">");
	  buf.append("Peer '"+HTMLEncoder.encode(pn.getName())+"' is not connected.  Not sending N2NTM.<br /><br />\n");
	  buf.append("</div>");
	  buf.append("</div>");
        } else if(pn.getPeerNodeStatus() == Node.PEER_NODE_STATUS_ROUTING_BACKED_OFF) {
          ctx.getPageMaker().makeHead(buf, "Node To Node Text Message Succeeded");
	  
	  buf.append("<div class=\"infobox infobox-warning\">");
	  buf.append("<div class=\"infobox-header\">");
	  buf.append("Sent, but Peer is Backed Off");
	  buf.append("</div>");
	  buf.append("<div class=\"infobox-content\">");
	  buf.append("Peer '"+HTMLEncoder.encode(pn.getName())+"' is \"backed off\".  N2NTM receipt may be significantly delayed.<br /><br />\n");
          
          usm.send(pn, n2ntm);
          Logger.normal(this, "Sent N2NTM to '"+pn.getName()+"': "+message);
	  
          buf.append("Message should be on it's way:<hr /><br /><br />"+messageTextBuf2+"<br /><br />\n");
	  buf.append("</div>");
	  buf.append("</div>");
        } else {
          ctx.getPageMaker().makeHead(buf, "Node To Node Text Message Succeeded");
	  
	  buf.append("<div class=\"infobox infobox-success\">");
	  buf.append("<div class=\"infobox-header\">");
	  buf.append("Message Sent");
	  buf.append("</div>");
	  buf.append("<div class=\"infobox-content\">");
	  buf.append("Sending N2NTM to peer '"+HTMLEncoder.encode(pn.getName())+"'.<br /><br />\n");  
	  
          usm.send(pn, n2ntm);
          Logger.normal(this, "Sent N2NTM to '"+pn.getName()+"': "+message);
	  
          buf.append("Message should be on it's way:<hr /><br /><br />"+messageTextBuf2+"<br /><br />\n");
	  buf.append("</div>");
	  buf.append("</div>");
        }
      } catch (NotConnectedException e) {
        buf.append("Got NotConnectedException sending message to Peer '"+HTMLEncoder.encode(pn.getName())+"'.  Can't send N2NTM.<br /><br />\n");
        Logger.error(this, "Caught NotConnectedException while trying to send n2ntm: "+e);
      }
      buf.append("<a href=\"/darknet/\">Back to Darknet page</a>\n");
      ctx.getPageMaker().makeTail(buf);
      this.writeReply(ctx, 200, "text/html", "OK", buf.toString());
      return;
    }
    MultiValueTable headers = new MultiValueTable();
    headers.put("Location", "/darknet/");
    ctx.sendReplyHeaders(302, "Found", headers, null, 0);
  }
}
