package freenet.clients.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;
import java.util.TimeZone;

import freenet.l10n.L10n;
import freenet.support.HTMLEncoder;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.URIPreEncoder;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.io.BucketTools;
import freenet.support.io.LineReadingInputStream;
import freenet.support.io.TooLongException;

/**
 * ToadletContext implementation, including all the icky HTTP parsing etc.
 * An actual ToadletContext object represents a request, after we have parsed the 
 * headers. It provides methods to send replies.
 * @author root
 *
 */
public class ToadletContextImpl implements ToadletContext {
	
	private final MultiValueTable headers;
	private final OutputStream sockOutputStream;
	private final PageMaker pagemaker;
	private final BucketFactory bf;
	private final ToadletContainer container;
	private final InetAddress remoteAddr;
	
	/** Is the context closed? If so, don't allow any more writes. This is because there
	 * may be later requests.
	 */
	private boolean closed;
	private boolean shouldDisconnect;
	
	public ToadletContextImpl(Socket sock, MultiValueTable headers, String CSSName, BucketFactory bf, PageMaker pageMaker, ToadletContainer container) throws IOException {
		this.headers = headers;
		this.closed = false;
		sockOutputStream = sock.getOutputStream();
		remoteAddr = sock.getInetAddress();
		if(Logger.shouldLog(Logger.DEBUG, this))
			Logger.debug(this, "Connection from "+remoteAddr);
		this.bf = bf;
		this.pagemaker = pageMaker;
		this.container = container;
	}
	
	private void close() {
		closed = true;
	}
	
	private void sendMethodNotAllowed(String method, boolean shouldDisconnect) throws ToadletContextClosedException, IOException {
		if(closed) throw new ToadletContextClosedException();
		MultiValueTable mvt = new MultiValueTable();
		mvt.put("Allow", "GET, PUT");
		sendError(sockOutputStream, 405, "Method Not Allowed", l10n("methodNotAllowed"), shouldDisconnect, mvt);
	}
	
	private static String l10n(String key) {
		return L10n.getString("ToadletContextImpl."+key);
	}

	private static String l10n(String key, String pattern, String value) {
		return L10n.getString("ToadletContextImpl."+key, new String[] { pattern }, new String[] { value });
	}

	/**
	 * Send an error message. Caller provides the HTTP code, reason string, and a message, which
	 * will become the title and the h1'ed contents of the error page. 
	 */
	private static void sendError(OutputStream os, int code, String httpReason, String message, boolean shouldDisconnect, MultiValueTable mvt) throws IOException {
		sendHTMLError(os, code, httpReason, "<html><head><title>"+message+"</title></head><body><h1>"+message+"</h1></body>", shouldDisconnect, mvt);
	}
	
	/**
	 * Send an error message, containing full HTML from a String.
	 * @param os The OutputStream to send the message to.
	 * @param code The HTTP status code.
	 * @param httpReason The HTTP reason string for the HTTP status code. Do not make stuff up,
	 * use the official reason string, or some browsers may break.
	 * @param htmlMessage The HTML string to send.
	 * @param disconnect Whether to disconnect from the client afterwards.
	 * @param mvt Any additional headers.
	 * @throws IOException If we could not send the error message.
	 */
	private static void sendHTMLError(OutputStream os, int code, String httpReason, String htmlMessage, boolean disconnect, MultiValueTable mvt) throws IOException {
		if(mvt == null) mvt = new MultiValueTable();
		byte[] messageBytes = htmlMessage.getBytes("UTF-8");
		sendReplyHeaders(os, code, httpReason, mvt, "text/html; charset=UTF-8", messageBytes.length, disconnect);
		os.write(messageBytes);
	}
	
	private void sendNoToadletError(boolean shouldDisconnect) throws ToadletContextClosedException, IOException {
		if(closed) throw new ToadletContextClosedException();
		sendError(sockOutputStream, 404, "Not Found", l10n("noSuchToadlet"), shouldDisconnect, null);
	}
	
	private static void sendURIParseError(OutputStream os, boolean shouldDisconnect, Throwable e) throws IOException {
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		e.printStackTrace(pw);
		pw.close();
		String message = "<html><head><title>"+l10n("uriParseErrorTitle")+"</title></head><body><p>"+HTMLEncoder.encode(e.getMessage())+"</p><pre>\n"+sw.toString();
		sendHTMLError(os, 400, "Bad Request", message, shouldDisconnect, null);
	}
	
	public void sendReplyHeaders(int replyCode, String replyDescription, MultiValueTable mvt, String mimeType, long contentLength) throws ToadletContextClosedException, IOException {
		if(closed) throw new ToadletContextClosedException();
		sendReplyHeaders(sockOutputStream, replyCode, replyDescription, mvt, mimeType, contentLength, shouldDisconnect);
	}
	
	public PageMaker getPageMaker() {
		return pagemaker;
	}
	
	public MultiValueTable getHeaders() {
		return headers;
	}
	
	static void sendReplyHeaders(OutputStream sockOutputStream, int replyCode, String replyDescription, MultiValueTable mvt, String mimeType, long contentLength, boolean disconnect) throws IOException {
		// Construct headers
		if(mvt == null)
			mvt = new MultiValueTable();
		if(mimeType != null)
			if(mimeType.equalsIgnoreCase("text/html")){
				mvt.put("content-type", mimeType+"; charset=UTF-8");
			}else{
				mvt.put("content-type", mimeType);
			}
		if(contentLength >= 0)
			mvt.put("content-length", Long.toString(contentLength));
		// FIXME allow caching on a config option.
		// For now cater to the paranoid.
		// Also this may fix a wierd bug...
		// All keys are lower-case
		mvt.put("expires", "Thu, 01 Jan 1970 00:00:00 GMT");
		// Sent now, expires now.
		String time = makeHTTPDate(System.currentTimeMillis());
		mvt.put("last-modified", time);
		mvt.put("date", time);
		mvt.put("pragma", "no-cache");
		mvt.put("cache-control", "max-age=0, must-revalidate, no-cache, no-store, post-check=0, pre-check=0");
		if(disconnect)
			mvt.put("connection", "close");
		else
			mvt.put("connection", "keep-alive");
		StringBuffer buf = new StringBuffer(1024);
		buf.append("HTTP/1.1 ");
		buf.append(replyCode);
		buf.append(' ');
		buf.append(replyDescription);
		buf.append("\r\n");
		for(Enumeration e = mvt.keys();e.hasMoreElements();) {
			String key = (String) e.nextElement();
			Object[] list = mvt.getArray(key);
			key = fixKey(key);
			for(int i=0;i<list.length;i++) {
				String val = (String) list[i];
				buf.append(key);
				buf.append(": ");
				buf.append(val);
				buf.append("\r\n");
			}
		}
		buf.append("\r\n");
		sockOutputStream.write(buf.toString().getBytes("US-ASCII"));
	}
	
	static TimeZone TZ_UTC = TimeZone.getTimeZone("UTC");
	
	private static String makeHTTPDate(long time) {
		// For HTTP, GMT == UTC
		SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'",Locale.US);
		sdf.setTimeZone(TZ_UTC);
		return sdf.format(new Date(time));
	}
	
	/** Fix key case to be conformant to HTTP expectations.
	 * Note that HTTP is case insensitive on header names, but we may as well
	 * send something as close to the spec as possible in case of broken clients... 
	 */
	private static String fixKey(String key) {
		StringBuffer sb = new StringBuffer(key.length());
		char prev = 0;
		for(int i=0;i<key.length();i++) {
			char c = key.charAt(i);
			if((i == 0) || (prev == '-')) {
				c = Character.toUpperCase(c);
			}
			sb.append(c);
			prev = c;
		}
		return sb.toString();
	}
	
	/**
	 * Handle an incoming connection. Blocking, obviously.
	 */
	public static void handle(Socket sock, ToadletContainer container, BucketFactory bf, PageMaker pageMaker) {
		try {
			InputStream is = sock.getInputStream();
			
			LineReadingInputStream lis = new LineReadingInputStream(is);
			
			while(true) {
				
				String firstLine = lis.readLine(32768, 128, false); // ISO-8859-1 or US-ASCII, _not_ UTF-8
				if (firstLine == null) {
					sock.close();
					return;
				} else if (firstLine.equals("")) {
					continue;
				}
				
				if(Logger.shouldLog(Logger.MINOR, ToadletContextImpl.class))
					Logger.minor(ToadletContextImpl.class, "first line: "+firstLine);
				
				String[] split = firstLine.split(" ");
				
				if(split.length != 3)
					throw new ParseException("Could not parse request line (split.length="+split.length+"): "+firstLine);
				
				if(!split[2].startsWith("HTTP/1."))
					throw new ParseException("Unrecognized protocol "+split[2]);
				
				URI uri;
				try {
					uri = URIPreEncoder.encodeURI(split[1]).normalize();
				} catch (URISyntaxException e) {
					sendURIParseError(sock.getOutputStream(), true, e);
					return;
				}
				
				String method = split[0];
				
				MultiValueTable headers = new MultiValueTable();
				
				while(true) {
					String line = lis.readLine(32768, 128, false); // ISO-8859 or US-ASCII, not UTF-8
					if (line == null) {
						sock.close();
						return;
					}
					//System.out.println("Length="+line.length()+": "+line);
					if(line.length() == 0) break;
					int index = line.indexOf(':');
					if (index < 0) {
						throw new ParseException("Missing ':' in request header field");
					}
					String before = line.substring(0, index).toLowerCase();
					String after = line.substring(index+1);
					after = after.trim();
					headers.put(before, after);
				}
				
				boolean shouldDisconnect = shouldDisconnectAfterHandled(split[2].equals("HTTP/1.0"), headers);
				
				ToadletContextImpl ctx = new ToadletContextImpl(sock, headers, container.getCSSName(), bf, pageMaker, container);
				ctx.shouldDisconnect = shouldDisconnect;
				
				/*
				 * if we're handling a POST, copy the data into a bucket now,
				 * before we go into the redirect loop
				 */
				
				Bucket data;
				
				if(method.equals("POST")) {
					String slen = (String) headers.get("content-length");
					if(slen == null) {
						sendError(sock.getOutputStream(), 400, "Bad Request", l10n("noContentLengthInPOST"), true, null);
						return;
					}
					long len;
					try {
						len = Integer.parseInt(slen);
						if(len < 0) throw new NumberFormatException("content-length less than 0");
					} catch (NumberFormatException e) {
						sendError(sock.getOutputStream(), 400, "Bad Request", l10n("cannotParseContentLengthWithError", "error", e.toString()), true, null);
						return;
					}
					data = bf.makeBucket(len);
					BucketTools.copyFrom(data, is, len);
				} else {
					// we're not doing to use it, but we have to keep
					// the compiler happy
					data = null;
				}
				
				// Handle it.
				boolean redirect = true;
				while (redirect) {
					// don't go around the loop unless set explicitly
					redirect = false;
					
					Toadlet t = container.findToadlet(uri);
					
					if(t == null) {
						ctx.sendNoToadletError(shouldDisconnect);
						break;
					}
					
					if(method.equals("GET")) {
						try {
							t.handleGet(uri, new HTTPRequestImpl(uri), ctx);
							ctx.close();
						} catch (RedirectException re) {
							uri = re.newuri;
							redirect = true;
						}
						
					} else if(method.equals("PUT")) {
						try {
							t.handlePut(uri, ctx);
							ctx.close();
						} catch (RedirectException re) {
							uri = re.newuri;
							redirect = true;
						}
						
					} else if(method.equals("POST")) {
						try {
							HTTPRequestImpl req = new HTTPRequestImpl(uri, data, ctx);
							t.handlePost(uri, req, ctx);
							req.freeParts();
						} catch (RedirectException re) {
							uri = re.newuri;
							redirect = true;
						}
						
					} else {
						ctx.sendMethodNotAllowed(method, shouldDisconnect);
						ctx.close();
					}
				}
				if(shouldDisconnect) {
					sock.close();
					return;
				}
			}
			
		} catch (ParseException e) {
			try {
				sendError(sock.getOutputStream(), 400, "Bad Request", l10n("parseErrorWithError", "error", e.getMessage()), true, null);
			} catch (IOException e1) {
				// Ignore
			}
		} catch (TooLongException e) {
			try {
				sendError(sock.getOutputStream(), 400, "Bad Request", l10n("headersLineTooLong"), true, null);
			} catch (IOException e1) {
				// Ignore
			}
		} catch (IOException e) {
			return;
		} catch (ToadletContextClosedException e) {
			Logger.error(ToadletContextImpl.class, "ToadletContextClosedException while handling connection!");
			return;
		}
	}
	
	/**
	 * Should the connection be closed after handling this request?
	 * @param isHTTP10 Did the client specify HTTP/1.0?
	 * @param headers Client headers.
	 * @return True if the connection should be closed.
	 */
	private static boolean shouldDisconnectAfterHandled(boolean isHTTP10, MultiValueTable headers) {
		String connection = (String) headers.get("connection");
		if(connection != null) {
			if(connection.equalsIgnoreCase("close"))
				return true;
			
			if(connection.equalsIgnoreCase("keep-alive"))
				return false;
		}
		if(isHTTP10 == true)
			return true;
		else
			// HTTP 1.1
			return false;
	}
	
	static class ParseException extends Exception {
		private static final long serialVersionUID = -1;
		
		ParseException(String string) {
			super(string);
		}
		
	}
	
	public void writeData(byte[] data, int offset, int length) throws ToadletContextClosedException, IOException {
		if(closed) throw new ToadletContextClosedException();
		sockOutputStream.write(data, offset, length);
	}
	
	public void writeData(byte[] data) throws ToadletContextClosedException, IOException {
		writeData(data, 0, data.length);
	}
	
	public void writeData(Bucket data) throws ToadletContextClosedException, IOException {
		if(closed) throw new ToadletContextClosedException();
		BucketTools.copyTo(data, sockOutputStream, Long.MAX_VALUE);
	}
	
	public BucketFactory getBucketFactory() {
		return bf;
	}

	public HTMLNode addFormChild(HTMLNode parentNode, String target, String name) {
		HTMLNode formNode =
			parentNode.addChild("form", new String[] { "action", "method", "enctype", "id", "name", "accept-charset" }, 
					new String[] { target, "post", "multipart/form-data", name, name, "utf-8"} );
		formNode.addChild("input", new String[] { "type", "name", "value" }, 
				new String[] { "hidden", "formPassword", container.getFormPassword() });
		
		return formNode;
	}

	public boolean isAllowedFullAccess() {
		return container.isAllowedFullAccess(remoteAddr);
	}

	public boolean doRobots() {
		return container.doRobots();
	}
}
