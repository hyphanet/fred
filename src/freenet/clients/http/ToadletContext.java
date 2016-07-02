package freenet.clients.http;

import java.io.IOException;
import java.net.URI;
import java.text.ParseException;
import java.util.Date;

import freenet.clients.http.FProxyFetchInProgress.REFILTER_POLICY;
import freenet.clients.http.bookmark.BookmarkManager;
import freenet.node.useralerts.UserAlertManager;
import freenet.support.HTMLNode;
import freenet.support.MultiValueTable;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.api.HTTPRequest;

/**
 * Object represents context for a single request. Is used as a token,
 * when the Toadlet wants to e.g. write a reply.
 */
public interface ToadletContext {

    /**
     * Write reply headers for generated content (web interface pages) and redirects etc.
     * @param code HTTP code.
     * @param desc HTTP code description.
     * @param mvt Any extra headers. Can be null.
     * @param mimeType The MIME type of the reply.
     * @param length The length of the reply.
     * @param forceDisableJavascript Disable javascript even if it is enabled for the web interface
     * as a whole.
     */
    void sendReplyHeaders(int code, String desc, MultiValueTable<String,String> mvt, String mimeType, long length) throws ToadletContextClosedException, IOException;
    
    /**
     * Write reply headers for generated content (web interface pages) and redirects etc.
     * @param code HTTP code.
     * @param desc HTTP code description.
     * @param mvt Any extra headers. Can be null.
     * @param mimeType The MIME type of the reply.
     * @param length The length of the reply.
     * @param forceDisableJavascript Disable javascript even if it is enabled for the web interface
     * as a whole.
     */
    void sendReplyHeaders(int code, String desc, MultiValueTable<String,String> mvt, String mimeType, long length, boolean forceDisableJavascript) throws ToadletContextClosedException, IOException;
    
    /**
     * @deprecated
     * Write reply headers for either generated content (web interface pages) or static content.
     * Callers should use either sendReplyHeaders() or sendReplyHeadersStatic()!
     */
    @Deprecated
    void sendReplyHeaders(int code, String desc, MultiValueTable<String,String> mvt, String mimeType, long length, Date mTime) throws ToadletContextClosedException, IOException;
    
	/**
	 * Write reply headers with a customised modification time for static content.
	 * @param code HTTP code.
	 * @param desc HTTP code description.
	 * @param mvt Any extra headers. Can be null.
	 * @param mimeType The MIME type of the reply.
	 * @param length The length of the reply.
	 * @param mTime The modification time of the data being sent.
	 */
	void sendReplyHeadersStatic(int code, String desc, MultiValueTable<String,String> mvt, String mimeType, long length, Date mTime) throws ToadletContextClosedException, IOException;
	
	/**
	 * Write reply headers for content downloaded from Freenet. Progress bars etc are not content 
	 * downloaded from Freenet, so are rendered using sendReplyHeaders(). For content downloaded 
	 * from Freenet we send headers which absolutely forbid Javascript, even if it somehow got 
	 * through the content filter.
	 * @param code HTTP code.
	 * @param desc HTTP code description.
	 * @param mvt Any extra headers. Can be null.
	 * @param mimeType The MIME type of the reply.
	 * @param length The length of the reply.
	 */
	void sendReplyHeadersFProxy(int code, String desc, MultiValueTable<String,String> mvt, String mimeType, long length) throws ToadletContextClosedException, IOException;

	/**
	 * Write data. Note you must send reply headers first.
	 */
	void writeData(byte[] data, int offset, int length) throws ToadletContextClosedException, IOException;

	/**
	 * Force a disconnection after handling this request. Used only when a throwable was thrown and we don't know
	 * what the state of the connection is. FIXME we could handle this better by remembering whether headers have
	 * been sent, how long the attached data should be, how much data has been sent etc.
	 */
	void forceDisconnect();
	
	/**
	 * Convenience method that simply calls {@link #writeData(byte[], int, int)}.
	 * 
	 * @param data
	 *            The data to write
	 * @throws ToadletContextClosedException
	 *             if the context has already been closed
	 * @throws IOException
	 *             if an I/O error occurs
	 */
	void writeData(byte[] data) throws ToadletContextClosedException, IOException;

	/**
	 * Write data from a bucket. You must send reply headers first.
	 *
	 * @param data The Bucket which contains the data. This function
	 *        assumes ownership of the Bucket, calling free() on it
	 *        when done. If this behavior is undesired, callers can
	 *        wrap their Bucket in a NoFreeBucket.
	 *
	 * @see freenet.support.io.NoFreeBucket
	 */
	void writeData(Bucket data) throws ToadletContextClosedException, IOException;
	
	/**
	 * Get the page maker object.
	 */
	PageMaker getPageMaker();
	
	/**
	 * Get the form password required for "dangerous" operations.
	 */
	String getFormPassword();

	/**
	 * Check a request for the form password, and send an error to the client if the password is
	 * not valid.
	 * @param request The request to check.
	 * @param redirectTo The location to redirect to if the password is not set.
	 * 
	 * @return Whether the request contains a valid form password
	 */
	boolean checkFormPassword(HTTPRequest request, String redirectTo) throws ToadletContextClosedException, IOException;
	
	/**
	 * Check a request for the form password, and send an error to the client if the password is
	 * not valid.
	 * @param request The request to check.
	 * @return Whether the request contains a valid form password
	 * @throws ToadletContextClosedException
	 * @throws IOException
	 */
	boolean checkFormPassword(HTTPRequest request) throws ToadletContextClosedException, IOException;

	/** Check a request for the form password. Some Toadlet's will want to e.g. send a confirmation page
	 * using the submitted data if the form password isn't present. 
	 * @throws IOException */
	boolean hasFormPassword(HTTPRequest request) throws IOException;
	
	   
    /**
     * Check a context for whether {@link #isAllowedFullAccess()} is true.
     * 
     * If it is false, an error page is sent to the client, and false is returned.
     * You can then abort processing of the request.
     * 
     * @return The return value of {@link #isAllowedFullAccess()}.
     * @throws IOException See {@link Toadlet#sendUnauthorizedPage(ToadletContext)}
     * @throws ToadletContextClosedException See {@link Toadlet#sendUnauthorizedPage(ToadletContext)}
     */
    boolean checkFullAccess(Toadlet toadlet) throws ToadletContextClosedException, IOException;
    
	/**
	 * Get the user alert manager.
	 */
	UserAlertManager getAlertManager();
	
	/**
	 * Get the bookmark manager.
	 */
	BookmarkManager getBookmarkManager();

	BucketFactory getBucketFactory();
	
	MultiValueTable<String, String> getHeaders();
	
	/**
	 * Get an existing {@link Cookie} (sent by the client) from the headers.
	 */
	ReceivedCookie getCookie(URI domain, URI path, String name) throws ParseException;
	
	/**
	 * Set a {@link Cookie}, it will be sent with the reply headers to the client.
	 */
	void setCookie(Cookie newCookie);

	/**
	 * Add a form node to an HTMLNode under construction. This will have the correct enctype and 
	 * formPassword set already, so all the caller needs to do is add its specific fields.
	 * @param parentNode The parent HTMLNode.
	 * @param target Where the form should be POSTed to.
	 * @param id HTML name for the form for stylesheet/script access. Will be added as both id and name.
	 * @return The form HTMLNode.
	 */
	HTMLNode addFormChild(HTMLNode parentNode, String target, String id);

	/** Is this Toadlet allowed full access to the node, including the ability to reconfigure it,
	 * restart it etc? */
	boolean isAllowedFullAccess();
	
	/**
	 * Is the web interface in advanced mode?
	 */
	boolean isAdvancedModeEnabled();

	/**
	 * Return a robots.txt excluding all spiders and other non-browser HTTP clients?
	 */
	boolean doRobots();

	ToadletContainer getContainer();

	boolean disableProgressPage();
	
	Toadlet activeToadlet();
	
	/** Returns the unique id of this request
	 * @return The unique id*/
	public String getUniqueId();
	
	public URI getUri();
	
	/** What to do when we find cached data on the global queue but it's already been 
	 * filtered, and we want a filtered copy. */
	REFILTER_POLICY getReFilterPolicy();
}

