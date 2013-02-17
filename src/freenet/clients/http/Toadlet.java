/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.FetchWaiter;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertBlock;
import freenet.client.InsertException;
import freenet.client.async.ClientGetter;
import freenet.keys.FreenetURI;
import freenet.l10n.NodeL10n;
import freenet.node.RequestClient;
import freenet.support.HTMLEncoder;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.api.Bucket;

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
 * NIO servlets approach). Obviously this assumes continuations, and that
 * we can suspend in a method on Toadlet ... which might be possible.
 * 
 * FIXME Investigate servlet 3.0, which support continuations.
 */
public abstract class Toadlet {
	
	public static final String HANDLE_METHOD_PREFIX = "handleMethod";

	public abstract String path();
	
	/** So an invisible toadlet can pretend to be a visible one for the sake of the
	 * menu. */
	public Toadlet showAsToadlet() {
		return this;
	}
	
	protected Toadlet(HighLevelSimpleClient client) {
		this.client = client;
	}

	final HighLevelSimpleClient client;
	ToadletContainer container;

	private String supportedMethodsCache;

	private static String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("Toadlet."+key, new String[] { pattern }, new String[] { value });
	}

	private static String l10n(String key) {
		return NodeL10n.getBase().getString("Toadlet."+key);
	}

	/**
	 * Which methods are supported by this Toadlet.
	 * Should return a string containing the methods supported, separated by commas
	 * For example: "GET, PUT" (in which case both 'handleMethodGET()' and 'handleMethodPUT()'
	 * must be implemented).
	 *
	 * IMPORTANT: This will discover inherited methods because of getMethod()
	 * below. If you do not want to expose a method implemented by a parent
	 * class, then *OVERRIDE THIS METHOD*.
	 */
	public final String findSupportedMethods() {
		if (supportedMethodsCache == null) {
			Method methlist[] = this.getClass().getMethods();
			StringBuilder sb = new StringBuilder();
			for (Method m: methlist) {
				String name = m.getName();
				if (name.startsWith(HANDLE_METHOD_PREFIX)) {
					sb.append(name.substring(HANDLE_METHOD_PREFIX.length()));
					sb.append(", ");
				}
			}
			if (sb.length() >= 2) {
				// remove last ", "
				sb.deleteCharAt(sb.length()-1);
				sb.deleteCharAt(sb.length()-1);
			}
			supportedMethodsCache = sb.toString();
		}
		return supportedMethodsCache;
	}

	/**
	 * Client calls from the above messages to run a Freenet request.
	 * This method may block (or suspend).
	 * @param maxSize Maximum length of returned content.
	 * @param clientContext Client context object. This should be the same for any group of related requests, but different
	 * for any two unrelated requests. Request selection round-robin's over these, within any priority and retry count class,
	 * and above the level of individual block fetches.
	 */
	FetchResult fetch(FreenetURI uri, long maxSize, RequestClient clientContext, FetchContext fctx) throws FetchException {
		// For now, just run it blocking.
		FetchWaiter fw = new FetchWaiter();
		@SuppressWarnings("unused")
		ClientGetter getter = client.fetch(uri, 1, clientContext, fw, fctx);
		return fw.waitForCompletion();

	}
	/**
	 * Returns a default FetchContext
	 * @param maxSize The maximum allowable size of the fetch's result
	 * @return A default FetchContext
	 */
	FetchContext getFetchContext(long maxSize) {
		//We want to retrieve a FetchContext we may override
		return client.getFetchContext(maxSize);
	}

	FreenetURI insert(InsertBlock insert, String filenameHint, boolean getCHKOnly) throws InsertException {
		// For now, just run it blocking.
		insert.desiredURI.checkInsertURI();
		return client.insert(insert, getCHKOnly, filenameHint);
	}

	/**
	 * Client calls to write a reply to the HTTP requester.
	 */
	protected void writeReply(ToadletContext ctx, int code, String mimeType, String desc, byte[] data, int offset, int length) throws ToadletContextClosedException, IOException {
		ctx.sendReplyHeaders(code, desc, null, mimeType, length);
		ctx.writeData(data, offset, length);
	}

	/**
	 * @param data The Bucket which contains the reply data. This
	 *        function assumes ownership of the Bucket, calling free()
	 *        on it when done. If this behavior is undesired, callers
	 *        can wrap their Bucket in a NoFreeBucket.
	 *
	 * @see freenet.support.io.NoFreeBucket
	 */
	protected void writeReply(ToadletContext ctx, int code, String mimeType, String desc, Bucket data) throws ToadletContextClosedException, IOException {
		writeReply(ctx, code, mimeType, desc, null, data);
	}
	
	/**
	 * @param data The Bucket which contains the reply data. This
	 *        function assumes ownership of the Bucket, calling free()
	 *        on it when done. If this behavior is undesired, callers
	 *        can wrap their Bucket in a NoFreeBucket.
	 *
	 * @see freenet.support.io.NoFreeBucket
	 */
	protected void writeReply(ToadletContext context, int code, String mimeType, String desc, MultiValueTable<String, String> headers, Bucket data) throws ToadletContextClosedException, IOException {
		context.sendReplyHeaders(code, desc, headers, mimeType, data.size());
		context.writeData(data);
	}

	protected void writeReply(ToadletContext ctx, int code, String mimeType, String desc, String reply) throws ToadletContextClosedException, IOException {
		writeReply(ctx, code, mimeType, desc, null, reply);
	}
	
	protected void writeHTMLReply(ToadletContext ctx, int code, String desc, String reply) throws ToadletContextClosedException, IOException {
		writeReply(ctx, code, "text/html; charset=utf-8", desc, null, reply);
	}
	
	protected void writeTextReply(ToadletContext ctx, int code, String desc, String reply) throws ToadletContextClosedException, IOException {
		writeReply(ctx, code, "text/plain; charset=utf-8", desc, null, reply);
	}
	
	protected void writeHTMLReply(ToadletContext ctx, int code, String desc, MultiValueTable<String, String> headers, String reply) throws ToadletContextClosedException, IOException {
		writeReply(ctx, code, "text/html; charset=utf-8", desc, headers, reply);
	}
	
	protected void writeTextReply(ToadletContext ctx, int code, String desc, MultiValueTable<String, String> headers, String reply) throws ToadletContextClosedException, IOException {
		writeReply(ctx, code, "text/plain; charset=utf-8", desc, headers, reply);
	}
	
	protected void writeReply(ToadletContext context, int code, String mimeType, String desc, MultiValueTable<String, String> headers, String reply) throws ToadletContextClosedException, IOException {
		byte[] buffer = reply.getBytes("UTF-8");
		writeReply(context, code, mimeType, desc, headers, buffer, 0, buffer.length);
	}
	
	protected void writeReply(ToadletContext context, int code, String mimeType, String desc, MultiValueTable<String, String> headers, byte[] buffer, int startIndex, int length) throws ToadletContextClosedException, IOException {
		context.sendReplyHeaders(code, desc, headers, mimeType, length);
		context.writeData(buffer, startIndex, length);
	}
	
	/**
	 * Do a permanent redirect (HTTP Status 301).
	 *
	 * This will write rudimentary HTML, but typically browsers will follow
	 * the Location header.
	 * TODO Refactor with writeTemporaryRedirect.
	 * @param ctx
	 * @param msg
	 * @param location
	 * @throws ToadletContextClosedException
	 * @throws IOException
	 */
	static void writePermanentRedirect(ToadletContext ctx, String msg, String location) throws ToadletContextClosedException, IOException {
		MultiValueTable<String, String> mvt = new MultiValueTable<String, String>();
		mvt.put("Location", location);
		if(msg == null) msg = "";
		else msg = HTMLEncoder.encode(msg);
		String redirDoc =
			"<html><head><title>"+msg+"</title></head><body><h1>" +
			l10n("permRedirectWithReason", "reason", msg)+
			"</h1><a href=\""+HTMLEncoder.encode(location)+"\">"+l10n("clickHere")+"</a></body></html>";
		byte[] buf;
		try {
			buf = redirDoc.getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
		}
		ctx.sendReplyHeaders(301, "Moved Permanently", mvt, "text/html; charset=UTF-8", buf.length);
		ctx.writeData(buf, 0, buf.length);
	}
	
	/**
	 * Do a temporary redirect (HTTP Status 302).
	 *
	 * This will write rudimentary HTML, but typically browsers will follow
	 * the Location header.
	 * TODO Refactor with writePermanentRedirect.
	 * @param ctx
	 * @param msg Message to be used in HTML (not visible in general).
	 * @param location New location (URL)
	 * @throws ToadletContextClosedException
	 * @throws IOException
	 */
	protected void writeTemporaryRedirect(ToadletContext ctx, String msg, String location) throws ToadletContextClosedException, IOException {
		MultiValueTable<String, String> mvt = new MultiValueTable<String, String>();
		mvt.put("Location", location);
		if(msg == null) msg = "";
		else msg = HTMLEncoder.encode(msg);
		String redirDoc =
			"<html><head><title>"+msg+"</title></head><body><h1>" +
			l10n("tempRedirectWithReason", "reason", msg)+
			"</h1><a href=\""+HTMLEncoder.encode(location)+"\">" +
			l10n("clickHere") + "</a></body></html>";
		byte[] buf;
		try {
			buf = redirDoc.getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
		}
		ctx.sendReplyHeaders(302, "Found", mvt, "text/html; charset=UTF-8", buf.length);
		ctx.writeData(buf, 0, buf.length);
	}
	
	/**
	 * Send a simple error page.
	 */
	protected void sendErrorPage(ToadletContext ctx, int code, String desc, String message) throws ToadletContextClosedException, IOException {
		sendErrorPage(ctx, code, desc, new HTMLNode("#", message));
	}

	/**
	 * Send a slightly more complex error page.
	 */
	protected void sendErrorPage(ToadletContext ctx, int code, String desc, HTMLNode message) throws ToadletContextClosedException, IOException {
		PageNode page = ctx.getPageMaker().getPageNode(desc, ctx);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;
		
		HTMLNode infoboxContent = ctx.getPageMaker().getInfobox("infobox-error", desc, contentNode, null, true);
		infoboxContent.addChild(message);
		infoboxContent.addChild("br");
		infoboxContent.addChild("a", "href", ".", l10n("returnToPrevPage"));
		infoboxContent.addChild("br");
		addHomepageLink(infoboxContent);
		
		writeHTMLReply(ctx, code, desc, pageNode.generate());
	}

	/**
	 * Send an error page from an exception.
	 * @param ctx The context object for this request.
	 * @param desc The title of the error page
	 * @param message The message to be sent to the user. The stack trace will follow.
	 * @param t The Throwable which caused the error.
	 * @throws IOException If there is an error writing the reply.
	 * @throws ToadletContextClosedException If the context has already been closed.
	 */
	protected void sendErrorPage(ToadletContext ctx, String desc, String message, Throwable t) throws ToadletContextClosedException, IOException {
		PageNode page = ctx.getPageMaker().getPageNode(desc, ctx);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;
		
		HTMLNode infoboxContent = ctx.getPageMaker().getInfobox("infobox-error", desc, contentNode, null, true);
		infoboxContent.addChild("#", message);
		infoboxContent.addChild("br");
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		pw.println(t);
		t.printStackTrace(pw);
		pw.close();
		// FIXME what is the modern (CSS/XHTML) equivalent of <pre>?
		infoboxContent.addChild("pre", sw.toString());
		infoboxContent.addChild("br");
		infoboxContent.addChild("a", "href", ".", l10n("returnToPrevPage"));
		addHomepageLink(infoboxContent);
		
		writeHTMLReply(ctx, 500, desc, pageNode.generate());
	}

	protected void writeInternalError(Throwable t, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		Logger.error(this, "Caught "+t, t);
		String msg = "<html><head><title>"+NodeL10n.getBase().getString("Toadlet.internalErrorTitle")+
				"</title></head><body><h1>"+NodeL10n.getBase().getString("Toadlet.internalErrorPleaseReport")+"</h1><pre>";
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		t.printStackTrace(pw);
		pw.flush();
		msg = msg + sw.toString() + "</pre></body></html>";
		writeHTMLReply(ctx, 500, "Internal Error", msg);
	}
	
	protected static void addHomepageLink(HTMLNode content) {
		content.addChild("a", new String[]{"href", "title"}, new String[]{"/", l10n("homepage")}, l10n("returnToNodeHomepage"));
	}

	/**
	 * Get the client impl. DO NOT call the blocking methods on it!!
	 * Just use it for configuration etc.
	 */
	protected HighLevelSimpleClient getClientImpl() {
		return client;
	}
}
