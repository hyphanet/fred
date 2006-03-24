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
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.HTMLEncoder;
import freenet.support.Logger;
import freenet.support.MultiValueTable;

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
	public void handleGet(URI uri, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		StringBuffer buf = new StringBuffer();
		
		ctx.getPageMaker().makeHead(buf, "Not supported");
		
		buf.append("Operation not supported");
		ctx.getPageMaker().makeTail(buf);
		
		MultiValueTable hdrtbl = new MultiValueTable();
		hdrtbl.put("Allow", this.supportedMethods());
		ctx.sendReplyHeaders(405, "Operation not Supported", hdrtbl, "text/html", buf.length());
		ctx.writeData(buf.toString().getBytes(), 0, buf.length());
	}
	

	/**
	 * Likewise for a PUT request.
	 */
	public void handlePut(URI uri, Bucket data, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		StringBuffer buf = new StringBuffer();
		
		ctx.getPageMaker().makeHead(buf, "Not supported");
		
		buf.append("Operation not supported");
		ctx.getPageMaker().makeTail(buf);
		
		MultiValueTable hdrtbl = new MultiValueTable();
		hdrtbl.put("Allow", this.supportedMethods());
		ctx.sendReplyHeaders(405, "Operation not Supported", hdrtbl, "text/html", buf.length());
		ctx.writeData(buf.toString().getBytes(), 0, buf.length());
	}
	
	public void handlePost(URI uri, Bucket data, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		StringBuffer buf = new StringBuffer();
		
		ctx.getPageMaker().makeHead(buf, "Not supported");
		
		buf.append("Operation not supported");
		ctx.getPageMaker().makeTail(buf);
		
		MultiValueTable hdrtbl = new MultiValueTable();
		hdrtbl.put("Allow", this.supportedMethods());
		ctx.sendReplyHeaders(405, "Operation not Supported", hdrtbl, "text/html", buf.length());
		ctx.writeData(buf.toString().getBytes(), 0, buf.length());
		Logger.minor(this, "POSTed data to "+uri+":\n"+BucketTools.toByteArray(data));
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
	 */
	FetchResult fetch(FreenetURI uri) throws FetchException {
		// For now, just run it blocking.
		return client.fetch(uri);
	}

	FreenetURI insert(InsertBlock insert, boolean getCHKOnly) throws InserterException {
		// For now, just run it blocking.
		return client.insert(insert, getCHKOnly);
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
		ctx.sendReplyHeaders(code, desc, null, mimeType, data.size());
		ctx.writeData(data);
	}

	protected void writeReply(ToadletContext ctx, int code, String mimeType, String desc, String reply) throws ToadletContextClosedException, IOException {
		byte[] buf = reply.getBytes("ISO-8859-1");
		ctx.sendReplyHeaders(code, desc, null, mimeType, buf.length);
		ctx.writeData(buf, 0, buf.length);
	}
	
	protected void writePermanentRedirect(ToadletContext ctx, String msg, String string) throws ToadletContextClosedException, IOException {
		MultiValueTable mvt = new MultiValueTable();
		mvt.put("Location", string);
		if(msg == null) msg = "";
		else msg = HTMLEncoder.encode(msg);
		String redirDoc =
			"<html><head><title>"+msg+"</title></head><body><h1>Permanent redirect: "+
			msg+"</h1><a href=\""+string+"\">Click here</a></body></html>";
		byte[] buf;
		try {
			buf = redirDoc.getBytes("ISO-8859-1");
		} catch (UnsupportedEncodingException e) {
			// No way!
			throw new Error(e);
		}
		ctx.sendReplyHeaders(301, "Permanent redirect", mvt, "text/html;charset=ISO-8859-1", buf.length);
		ctx.writeData(buf, 0, buf.length);
	}
	
	/**
	 * Send a simple error page.
	 */
	protected void sendErrorPage(ToadletContext ctx, int code, String desc, String message) throws ToadletContextClosedException, IOException {
		StringBuffer buf = new StringBuffer();
			
		ctx.getPageMaker().makeHead(buf, desc);
		buf.append(message);
		ctx.getPageMaker().makeTail(buf);
		writeReply(ctx, code, "text/html", desc, buf.toString());
	}
	
	/**
	 * Get the client impl. DO NOT call the blocking methods on it!!
	 * Just use it for configuration etc.
	 */
	protected HighLevelSimpleClient getClientImpl() {
		return client;
	}
}
