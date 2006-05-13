package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.ClientMetadata;
import freenet.client.DefaultMIMETypes;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertBlock;
import freenet.client.InserterException;
import freenet.clients.http.filter.MIMEType;
import freenet.config.SubConfig;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.support.Bucket;


public class BlackOpsToadlet extends Toadlet {
	Node node;
	SubConfig config;
	BookmarkManager bookmarks;
	
	BlackOpsToadlet(HighLevelSimpleClient client, Node n) {
		super(client);
		this.node = n;
	}

	public void handlePost(URI uri, Bucket data, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		
		HTTPRequest request = new HTTPRequest(uri,data,ctx);
		if(request==null) return;
		
		StringBuffer buf = new StringBuffer();
		

			FreenetURI key = new FreenetURI(request.getPartAsString("key",128));
			ClientMetadata contentType = new ClientMetadata(request.getPartAsString("content-type",128));
			
			Bucket bucket = request.getPart("filename");
			
			InsertBlock block = new InsertBlock(bucket, contentType, key);
			try {
				ctx.getPageMaker().makeHead(buf, "Insertion");
				buf.append("<div class=\"infobox\">\n");
				key = this.insert(block, false);
				buf.append("The key : <a href=\"/" + key.getKeyType() + "@" + key.getGuessableKey() + "\">" +
						key.getKeyType() + "@" + key.getGuessableKey() +"</a> ("+bucket.getName()+") has been inserted successfully.<br>");
			} catch (InserterException e) {
				
				buf.append("Error: "+e.getMessage()+"<br>");
				if(e.uri != null)
					buf.append("URI would have been: "+e.uri+"<br>");
				int mode = e.getMode();
				if(mode == InserterException.FATAL_ERRORS_IN_BLOCKS || mode == InserterException.TOO_MANY_RETRIES_IN_BLOCKS) {
					buf.append("Splitfile-specific error:\n"+e.errorCodes.toVerboseString()+"<br>");
				}
			}
			bucket.free();
			
			buf.append("<br><a href=\"javascript:back()\" title=\"Back\">Back</a>\n");
        	buf.append("<br><a href=\"/\" title=\"Node Homepage\">Homepage</a>\n");
			buf.append("</div>\n");
			
			request.freeParts();
			ctx.getPageMaker().makeTail(buf);
			writeReply(ctx, 200, "text/html", "OK", buf.toString());
	}
	
	public void handleGet(URI uri, ToadletContext ctx) throws ToadletContextClosedException, IOException {}
		
	public String supportedMethods() {
		return "POST";
	}
}

