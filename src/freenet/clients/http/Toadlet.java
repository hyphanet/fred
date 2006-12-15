/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertBlock;
import freenet.client.InserterException;
import freenet.keys.FreenetURI;
import freenet.support.HTMLEncoder;
import freenet.support.HTMLNode;
import freenet.support.MultiValueTable;
import freenet.support.api.Bucket;
import freenet.support.api.HTTPRequest;

/**
 * Replacement for servlets. Just an easy to use HTTP interface, which is
 * compatible with continuations (eventually). You must extend this class
 * and provide the abstract methods. Apologies but we can't do it as an
 * interface and still have continuation compatibility; we can only 
 * suspend in a member function at this level in most implementations.
 * 
 * When we eventually implement continuations, these will require very
 * little thread overhead: We can suspend while running a freenet
 * request, and only grab a thread when we are either doing I/O or doing
 * computation in the derived class. We can suspend while doing I/O too;
 * on systems with NIO, we use that, on systems without it, we just run
 * the fetch on another (or this) thread. With no need to change any
 * APIs, and no danger of exploding memory use (unlike the traditional
 * NIO servlets approach).
 */
public abstract class Toadlet {

	protected Toadlet(HighLevelSimpleClient client) {
		this.client = client;
	}

	private final HighLevelSimpleClient client;
	ToadletContainer container;

	/**
	 * Handle a GET request.
	 * If not overridden by the client, send 'Method not supported'
	 * @param uri The URI (relative to this client's document root) to
	 * be fetched.
	 * @throws IOException 
	 * @throws ToadletContextClosedException 
	 */
	public void handleGet(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		handleUnhandledRequest(uri, null, ctx);
	}
	
	/**
	 * Likewise for a PUT request.
	 */
	public void handlePut(URI uri, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		handleUnhandledRequest(uri, null, ctx);
	}
	
	public void handlePost(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		handleUnhandledRequest(uri, null, ctx);
	}
	
	private void handleUnhandledRequest(URI uri, Bucket data, ToadletContext toadletContext) throws ToadletContextClosedException, IOException, RedirectException {
		HTMLNode pageNode = toadletContext.getPageMaker().getPageNode("Not supported");
		HTMLNode contentNode = toadletContext.getPageMaker().getContentNode(pageNode);

		HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-error");
		infobox.addChild("div", "class", "infobox-header", "Not supported");
		infobox.addChild("div", "class", "infobox-content", "Your browser sent a request that Freenet could not understand.");

		MultiValueTable hdrtbl = new MultiValueTable();
		hdrtbl.put("Allow", this.supportedMethods());

		StringBuffer pageBuffer = new StringBuffer();
		pageNode.generate(pageBuffer);
		toadletContext.sendReplyHeaders(405, "Operation not Supported", hdrtbl, "text/html; charset=utf-8", pageBuffer.length());
		toadletContext.writeData(pageBuffer.toString().getBytes());
	}
	
	/**
	 * Which methods are supported by this Toadlet.
	 * Should return a string containing the methods supported, separated by commas
	 * For example: "GET, PUT" (in which case both 'handleGet()' and 'handlePut()'
	 * must be overridden).
	 */					
	abstract public String supportedMethods();
	
	/**
	 * Client calls from the above messages to run a freenet request.
	 * This method may block (or suspend).
	 * @param maxSize Maximum length of returned content.
	 * @param clientContext Client context object. This should be the same for any group of related requests, but different
	 * for any two unrelated requests. Request selection round-robin's over these, within any priority and retry count class,
	 * and above the level of individual block fetches.
	 */
	FetchResult fetch(FreenetURI uri, long maxSize, Object clientContext) throws FetchException {
		// For now, just run it blocking.
		return client.fetch(uri, maxSize, clientContext);
	}

	FreenetURI insert(InsertBlock insert, String filenameHint, boolean getCHKOnly) throws InserterException {
		// For now, just run it blocking.
		insert.desiredURI.checkInsertURI();
		return client.insert(insert, getCHKOnly, filenameHint);
	}

	/**
	 * Client calls to write a reply to the HTTP requestor.
	 */
	protected void writeReply(ToadletContext ctx, int code, String mimeType, String desc, byte[] data, int offset, int length) throws ToadletContextClosedException, IOException {
		ctx.sendReplyHeaders(code, desc, null, mimeType, length);
		ctx.writeData(data, offset, length);
	}

	/**
	 * Client calls to write a reply to the HTTP requestor.
	 */
	protected void writeReply(ToadletContext ctx, int code, String mimeType, String desc, Bucket data) throws ToadletContextClosedException, IOException {
		writeReply(ctx, code, mimeType, desc, null, data);
	}
	
	protected void writeReply(ToadletContext context, int code, String mimeType, String desc, MultiValueTable headers, Bucket data) throws ToadletContextClosedException, IOException {
		context.sendReplyHeaders(code, desc, headers, mimeType, data.size());
		context.writeData(data);
	}

	protected void writeReply(ToadletContext ctx, int code, String mimeType, String desc, String reply) throws ToadletContextClosedException, IOException {
		writeReply(ctx, code, mimeType, desc, null, reply);
	}
	
	protected void writeReply(ToadletContext context, int code, String mimeType, String desc, MultiValueTable headers, String reply) throws ToadletContextClosedException, IOException {
		byte[] buffer = reply.getBytes("UTF-8");
		writeReply(context, code, mimeType, desc, headers, buffer, 0, buffer.length);
	}
	
	protected void writeReply(ToadletContext context, int code, String mimeType, String desc, MultiValueTable headers, byte[] buffer, int startIndex, int length) throws ToadletContextClosedException, IOException {
		context.sendReplyHeaders(code, desc, headers, mimeType, length);
		context.writeData(buffer, startIndex, length);
	}
	
	protected void writePermanentRedirect(ToadletContext ctx, String msg, String location) throws ToadletContextClosedException, IOException {
		MultiValueTable mvt = new MultiValueTable();
		mvt.put("Location", location);
		if(msg == null) msg = "";
		else msg = HTMLEncoder.encode(msg);
		String redirDoc =
			"<html><head><title>"+msg+"</title></head><body><h1>Permanent redirect: "+
			msg+"</h1><a href=\""+HTMLEncoder.encode(location)+"\">Click here</a></body></html>";
		byte[] buf;
		try {
			buf = redirDoc.getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			// No way!
			throw new Error(e);
		}
		ctx.sendReplyHeaders(301, "Permanent redirect", mvt, "text/html; charset=UTF-8", buf.length);
		ctx.writeData(buf, 0, buf.length);
	}
	
	/**
	 * Send a simple error page.
	 */
	protected void sendErrorPage(ToadletContext ctx, int code, String desc, String message) throws ToadletContextClosedException, IOException {
		HTMLNode pageNode = ctx.getPageMaker().getPageNode(desc);
		HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
		
		HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-error", desc));
		HTMLNode infoboxContent = ctx.getPageMaker().getContentNode(infobox);
		infoboxContent.addChild("#", message);
		infoboxContent.addChild("br");
		infoboxContent.addChild("a", "href", ".", "Return to the previous page.");
		infoboxContent.addChild("a", "href", "/", "Return to the main page.");
		
		writeReply(ctx, code, "text/html; charset=UTF-8", desc, pageNode.generate());
	}

	/**
	 * Send a slightly more complex error page.
	 */
	protected void sendErrorPage(ToadletContext ctx, int code, String desc, HTMLNode message) throws ToadletContextClosedException, IOException {
		HTMLNode pageNode = ctx.getPageMaker().getPageNode(desc);
		HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
		
		HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-error", desc));
		HTMLNode infoboxContent = ctx.getPageMaker().getContentNode(infobox);
		infoboxContent.addChild(message);
		infoboxContent.addChild("br");
		infoboxContent.addChild("a", "href", ".", "Return to the previous page.");
		infoboxContent.addChild("a", "href", "/", "Return to the main page.");
		
		writeReply(ctx, code, "text/html; charset=UTF-8", desc, pageNode.generate());
	}

	/**
	 * Get the client impl. DO NOT call the blocking methods on it!!
	 * Just use it for configuration etc.
	 */
	protected HighLevelSimpleClient getClientImpl() {
		return client;
	}
}
