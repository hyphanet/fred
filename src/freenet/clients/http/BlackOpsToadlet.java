package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.ClientMetadata;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertBlock;
import freenet.client.InserterException;
import freenet.config.SubConfig;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.support.Bucket;
import freenet.support.MultiValueTable;


public class BlackOpsToadlet extends Toadlet {
	Node node;
	SubConfig config;
	
	BlackOpsToadlet(HighLevelSimpleClient client, Node n) {
		super(client);
		this.node = n;
	}

	public void handlePost(URI uri, Bucket data, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		
		HTTPRequest request = new HTTPRequest(uri,data,ctx);
		if(request==null) return;
		
		StringBuffer buf = new StringBuffer();
		

			FreenetURI key = new FreenetURI(request.getPartAsString("key",128));
			String type = request.getPartAsString("content-type",128);
			if(type==null) type = "text/plain";
			ClientMetadata contentType = new ClientMetadata(type);
			
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
			
			
			buf.append("<br><a href=\"javascript:back()\" title=\"Back\">Back</a>\n");
        	buf.append("<br><a href=\"/\" title=\"Node Homepage\">Homepage</a>\n");
			buf.append("</div>\n");
			
			ctx.getPageMaker().makeTail(buf);
			writeReply(ctx, 200, "text/html", "OK", buf.toString());
			request.freeParts();
			bucket.free();
	}
	
	public void handleGet(URI uri, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		StringBuffer buf = new StringBuffer();
		
		ctx.getPageMaker().makeHead(buf, "Basic NIM form");
		
		buf.append("<div><form action=\"/system/\" method=\"POST\" enctype=\"multipart/form-data\">");
		buf.append("key : <input type=\"text\" value=\"KSK@key\" ><br>");
		buf.append("metadata : <select name=\"content-type\">");
		buf.append("<option value=\"text/plain\">text/plain</option>");
		buf.append("<option value=\"text/html\">text/html</option>");
		buf.append("<option value=\"audio/mpeg\">MP3 music</option>");
		buf.append("<option value=\"application/octet-stream\">application/octet-stream</option>");
		buf.append("</select><br>");
		buf.append("file: <input type=\"file\" name=\"filename\" value=\"/path/to/file\"><br>");
		buf.append("<input type=\"submit\"><input type=\"reset\"><br>");
		buf.append("</form><div>");
		
		ctx.getPageMaker().makeTail(buf);
		
		this.writeReply(ctx, 200, "text/html", "OK", buf.toString());
		}
		
	public String supportedMethods() {
		return "GET, POST";
	}
}