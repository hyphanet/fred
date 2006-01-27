package freenet.clients.http;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URI;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.keys.FreenetURI;
import freenet.support.Bucket;
import freenet.support.HTMLEncoder;
import freenet.support.Logger;

public class FproxyToadlet extends Toadlet {

	public FproxyToadlet(HighLevelSimpleClient client) {
		super(client);
	}

	void handleGet(URI uri, ToadletContext ctx)
			throws ToadletContextClosedException, IOException {
		String ks = uri.toString();
		if(ks.startsWith("/"))
			ks = ks.substring(1);
		FreenetURI key;
		try {
			key = new FreenetURI(ks);
		} catch (MalformedURLException e) {
			this.writeReply(ctx, 400, "text/html", "Invalid key", "<html><head><title>Invalid key</title></head><body>Expected a freenet key, but got "+HTMLEncoder.encode(ks)+"</body></html>");
			return;
		}
		try {
			Logger.minor(this, "Fproxy fetching "+key);
			FetchResult result = fetch(key);
			writeReply(ctx, 200, result.getMimeType(), "OK", result.asBucket());
		} catch (FetchException e) {
			String msg = e.getMessage();
			String extra = "";
			if(e.errorCodes != null)
				extra = "<pre>"+e.errorCodes.toVerboseString()+"</pre>";
			this.writeReply(ctx, 500 /* close enough - FIXME probably should depend on status code */,
					"text/html", msg, "<html><head><title>"+msg+"</title></head><body>Error: "+HTMLEncoder.encode(msg)+extra+"</body></html>");
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t, t);
			String msg = "<html><head><title>Internal Error</title></head><body><h1>Internal Error: please report</h1><pre>";
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			t.printStackTrace(pw);
			pw.flush();
			msg = msg + sw.toString() + "</pre></body></html>";
			this.writeReply(ctx, 500, "text/html", "Internal Error", msg);
		}
	}

	void handlePut(URI uri, Bucket data, ToadletContext ctx)
			throws ToadletContextClosedException, IOException {
		String notSupported = "<html><head><title>Not supported</title></head><body>"+
		"Operation not supported</body>";
		// FIXME should be 405? Need to let toadlets indicate what is allowed maybe in a callback?
		this.writeReply(ctx, 200, "text/html", "OK", notSupported);
	}

}
