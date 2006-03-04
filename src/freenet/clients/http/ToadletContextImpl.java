package freenet.clients.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Enumeration;

import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.URLDecoder;
import freenet.support.URLEncodedFormatException;
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

	private final Socket sock;
	private final MultiValueTable headers;
	private final OutputStream sockOutputStream;
	/** Is the context closed? If so, don't allow any more writes. This is because there
	 * may be later requests.
	 */
	private boolean closed;
	
	public ToadletContextImpl(Socket sock, MultiValueTable headers) throws IOException {
		this.sock = sock;
		this.headers = headers;
		this.closed = false;
		sockOutputStream = sock.getOutputStream();
	}

	private void close() {
		closed = true;
	}

	private void sendMethodNotAllowed(String method, boolean shouldDisconnect) throws ToadletContextClosedException, IOException {
		if(closed) throw new ToadletContextClosedException();
		MultiValueTable mvt = new MultiValueTable();
		mvt.put("Allow", "GET, PUT");
		sendError(sockOutputStream, 405, "Method not allowed", shouldDisconnect, mvt);
	}

	private static void sendError(OutputStream os, int code, String message, boolean shouldDisconnect, MultiValueTable mvt) throws IOException {
		sendError(os, code, message, "<html><head><title>"+message+"</title></head><body><h1>"+message+"</h1></body>", shouldDisconnect, mvt);
	}
	
	private static void sendError(OutputStream os, int code, String message, String htmlMessage, boolean disconnect, MultiValueTable mvt) throws IOException {
		if(mvt == null) mvt = new MultiValueTable();
		if(disconnect)
			mvt.put("Connection", "close");
		byte[] messageBytes = htmlMessage.getBytes("ISO-8859-1");
		sendReplyHeaders(os, code, message, mvt, "text/html", messageBytes.length);
		os.write(messageBytes);
	}
	
	private void sendNoToadletError(boolean shouldDisconnect) throws ToadletContextClosedException, IOException {
		if(closed) throw new ToadletContextClosedException();
		sendError(sockOutputStream, 404, "Service not found", shouldDisconnect, null);
	}
	
	private static void sendURIParseError(OutputStream os, boolean shouldDisconnect) throws IOException {
		sendError(os, 400, "URI parse error", shouldDisconnect, null);
	}

	public void sendReplyHeaders(int replyCode, String replyDescription, MultiValueTable mvt, String mimeType, long contentLength) throws ToadletContextClosedException, IOException {
		if(closed) throw new ToadletContextClosedException();
		sendReplyHeaders(sockOutputStream, replyCode, replyDescription, mvt, mimeType, contentLength);
	}

	static void sendReplyHeaders(OutputStream sockOutputStream, int replyCode, String replyDescription, MultiValueTable mvt, String mimeType, long contentLength) throws IOException {
		// Construct headers
		if(mvt == null)
			mvt = new MultiValueTable();
		if(mimeType != null)
			mvt.put("content-type", mimeType);
		if(contentLength >= 0)
			mvt.put("content-length", Long.toString(contentLength));
		StringBuffer buf = new StringBuffer(1024);
		buf.append("HTTP/1.1 ");
		buf.append(replyCode);
		buf.append(' ');
		buf.append(replyDescription);
		buf.append("\r\n");
		for(Enumeration e = mvt.keys();e.hasMoreElements();) {
			String key = (String) e.nextElement();
			Object[] list = mvt.getArray(key);
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
	
	/**
	 * Handle an incoming connection. Blocking, obviously.
	 */
	public static void handle(Socket sock, ToadletContainer container) {
		try {
			InputStream is = sock.getInputStream();
			
			LineReadingInputStream lis = new LineReadingInputStream(is);

			while(true) {
				
				String firstLine = lis.readLine(32768, 128);
				
				Logger.minor(ToadletContextImpl.class, "first line: "+firstLine);
				
				String[] split = firstLine.split(" ");
				
				if(split.length != 3)
					throw new ParseException("Could not parse request line (split.length="+split.length+"): "+firstLine);
				
				if(!split[2].startsWith("HTTP/1."))
					throw new ParseException("Unrecognized protocol "+split[2]);
				
				URI uri;
				try {
					//uri = new URI(URLDecoder.decode(split[1]));
					uri = new URI(split[1]);
				} catch (URISyntaxException e) {
					sendURIParseError(sock.getOutputStream(), true);
					return;
				/*
				} catch (URLEncodedFormatException e) {
					sendURIParseError(sock.getOutputStream(), true);
					return;
					*/
				}
				
				String method = split[0];
				
				MultiValueTable headers = new MultiValueTable();
				
				while(true) {
					String line = lis.readLine(32768, 128);
					//System.out.println("Length="+line.length()+": "+line);
					if(line.length() == 0) break;
					int index = line.indexOf(':');
					if (index < 0) {
						throw new ParseException("Missing ':' in request header field");
					}
					String before = line.substring(0, index);
					String after = line.substring(index+1);
					after = after.trim();
					headers.put(before, after);
				}
				
				// Handle it.
				
				Toadlet t = container.findToadlet(uri);
				
				ToadletContextImpl ctx = new ToadletContextImpl(sock, headers);
				
				boolean shouldDisconnect = shouldDisconnectAfterHandled(split[2].equals("HTTP/1.0"), headers);
				
				if(t == null)
					ctx.sendNoToadletError(shouldDisconnect);
				
				if(method.equals("GET")) {
					
					t.handleGet(uri, ctx);
					ctx.close();
					
				} else if(method.equals("PUT")) {
			
					t.handlePut(uri, null, ctx);
					ctx.close();

				} else if(method.equals("POST")) {
					
					Logger.error(ToadletContextImpl.class, "POST not supported");
					ctx.sendMethodNotAllowed(method, shouldDisconnect);
					ctx.close();
					
				} else {
					ctx.sendMethodNotAllowed(method, shouldDisconnect);
					ctx.close();
				}
				
				if(shouldDisconnect) {
					sock.close();
					return;
				}
			}
			
		} catch (ParseException e) {
			try {
				sendError(sock.getOutputStream(), 400, "Parse error: "+e.getMessage(), true, null);
			} catch (IOException e1) {
				// Ignore
			}
		} catch (TooLongException e) {
			try {
				sendError(sock.getOutputStream(), 400, "Line too long parsing headers", true, null);
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

	private static boolean shouldDisconnectAfterHandled(boolean isHTTP10, MultiValueTable headers) {
		String connection = (String) headers.get("connection");
		if(connection != null) {
			if(connection.equalsIgnoreCase("close"))
				return true;
			
			if(connection.equalsIgnoreCase("keep-alive"))
				return false;
		}
		if(!isHTTP10) return true;
		// WTF?
		return true;
	}
	
	static class ParseException extends Exception {

		ParseException(String string) {
			super(string);
		}

	}

	public void writeData(byte[] data, int offset, int length) throws ToadletContextClosedException, IOException {
		if(closed) throw new ToadletContextClosedException();
		sockOutputStream.write(data, offset, length);
	}

	public void writeData(Bucket data) throws ToadletContextClosedException, IOException {
		if(closed) throw new ToadletContextClosedException();
		BucketTools.copyTo(data, sockOutputStream, Long.MAX_VALUE);
	}

}
