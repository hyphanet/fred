package freenet.clients.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;

import freenet.client.DefaultMIMETypes;
import freenet.client.HighLevelSimpleClient;
import freenet.support.api.Bucket;

/**
 * Static Toadlet.
 * Serve up static files
 */
public class StaticToadlet extends Toadlet {
	StaticToadlet(HighLevelSimpleClient client) {
		super(client);
	}
	
	private static final String ROOT_URL = "/static/";
	private static final String ROOT_PATH = "staticfiles/";
	
	public void handleGet(URI uri, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		String path = uri.getPath();
		
		if (!path.startsWith(ROOT_URL)) {
			// we should never get any other path anyway
			return;
		}
		try {
			path = path.substring(ROOT_URL.length());
		} catch (IndexOutOfBoundsException ioobe) {
			this.sendErrorPage(ctx, 404, "Path not found", "The path you specified doesn't exist");
			return;
		}
		
		// be very strict about what characters we allow in the path, since
		if (!path.matches("^[A-Za-z0-9\\._\\/\\-]*$") || (path.indexOf("..") != -1)) {
			this.sendErrorPage(ctx, 404, "Path not found", "The given URI contains disallowed characters.");
			return;
		}
		
		InputStream strm = getClass().getResourceAsStream(ROOT_PATH+path);
		if (strm == null) {
			this.sendErrorPage(ctx, 404, "Path not found", "The specified path does not exist.");
			return;
		}
		Bucket data = ctx.getBucketFactory().makeBucket(strm.available());
		OutputStream os = data.getOutputStream();
		byte[] cbuf = new byte[4096];
		while(true) {
			int r = strm.read(cbuf);
			if(r == -1) break;
			os.write(cbuf, 0, r);
		}
		strm.close();
		os.close();
		
		ctx.sendReplyHeaders(200, "OK", null, DefaultMIMETypes.guessMIMEType(path, false), data.size());

		ctx.writeData(data);
		data.free();
	}
	
	public String supportedMethods() {
		return "GET";
	}

}
