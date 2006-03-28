package freenet.clients.http;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import freenet.client.HighLevelSimpleClient;
import freenet.config.Config;
import freenet.config.Option;
import freenet.config.SubConfig;
import freenet.pluginmanager.HTTPRequest;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.Logger;


// FIXME: add logging, comments
public class ConfigToadlet extends Toadlet {
	private Config config;
	
	ConfigToadlet(HighLevelSimpleClient client, Config conf) {
		super(client);
		config=conf;
	}

	public void handlePost(URI uri, Bucket data, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		StringBuffer errbuf = new StringBuffer();
		SubConfig[] sc = config.getConfigs();
		
		
		if(data.size() > 1024*1024) {
			this.writeReply(ctx, 400, "text/plain", "Too big", "Too much data, config servlet limited to 1MB");
			return;
		}
		byte[] d = BucketTools.toByteArray(data);
		String s = new String(d, "us-ascii");
		HTTPRequest request;
		try {
			request = new HTTPRequest("/", s);
		} catch (URISyntaxException e) {
			Logger.error(this, "Impossible: "+e, e);
			return;
		}
		for(int i=0; i<sc.length ; i++){
			Option[] o = sc[i].getOptions();
			String prefix = new String(sc[i].getPrefix());
			String configName;
			
			for(int j=0; j<o.length; j++){
				configName=o[j].getName();
				Logger.minor(this, "Setting "+prefix+"."+configName);
				
				// we ignore unreconized parameters 
				if(request.getParam(prefix+"."+configName) != ""){
					if(!(o[j].getValueString().equals(request.getParam(prefix+"."+configName)))){
						Logger.minor(this, "Setting "+prefix+"."+configName+" to "+request.getParam(prefix+"."+configName));
						try{
							o[j].setValue(request.getParam(prefix+"."+configName));
						}catch(Exception e){
							errbuf.append(o[j].getName()+" "+e+"\n");
						}
					}
				}
			}
		}
		config.store();
		
		StringBuffer outbuf = new StringBuffer();
		
		ctx.getPageMaker().makeHead(outbuf, "Configuration Applied");
		outbuf.append("<div class=\"infobox\">\n");
		if (errbuf.length() == 0) {
			outbuf.append("Your configuration changes were applied successfully<br />\n");
		} else {
			outbuf.append("Your configuration changes were applied with the following exceptions:<br />\n");
			outbuf.append(errbuf.toString());
			outbuf.append("<br />\n");
		}
		
		outbuf.append("<a href=\".\" title=\"Configuration\">Return to Node Configuration</a><br />\n");
		outbuf.append("<a href=\"/\" title=\"Node Homepage\">Homepage</a>\n");
		
		outbuf.append("</div>\n");
		
		ctx.getPageMaker().makeTail(outbuf);
		writeReply(ctx, 200, "text/html", "OK", outbuf.toString());
		
	}
	
	public void handleGet(URI uri, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		StringBuffer buf = new StringBuffer();
		SubConfig[] sc = config.getConfigs();
		
		HTTPRequest request = new HTTPRequest(uri);
		ctx.getPageMaker().makeHead(buf, "Freenet Node Configuration");
		buf.append("<form method=\"post\" action=\".\">");
		buf.append("<div class=\"config\">\n");
		
		String last = null;
		
		for(int i=0; i<sc.length;i++){
			Option[] o = sc[i].getOptions();
			//String prefix = new String(sc[i].getPrefix());
			
			/*
			if(last == null || ! last.equalsIgnoreCase(prefix)){
				//buf.append("</p>\n");
				buf.append("</span>\n");
				buf.append("<span id=\""+prefix+"\">\n");
				//buf.append("<p>\n");
			}
			*/
			
			buf.append("<ul class=\"config\">\n");
			
			for(int j=0; j<o.length; j++){
				String configName = new String(o[j].getName());
				/*
				if(prefix.equals("node") && configName.equals("name")){
					buf.append("<form method=\"post\"><input alt=\"node name\" class=\"config\"" +
							" type=\"text\" name=\"__node_name\" value=\""+o[j].getValueString()+"\"/></form>\n");
				}
				*/
				
				buf.append("<li><span class=\"configdesc\">");
				buf.append(o[j].getShortDesc());
				buf.append("</span> <span class=\"configkey\">");
				buf.append(sc[i].getPrefix()+"."+configName+"</span><input alt=\""+o[j].getShortDesc()+"\" class=\"config\"" +
						" type=\"text\" name=\""+sc[i].getPrefix()+"."+configName+"\" value=\""+o[j].getValueString()+"\" /></li>\n");
			}
			
			
			buf.append("</ul>\n");
		}
		
		buf.append("<br />");
		buf.append("<input type=\"submit\" value=\"Apply\" />");
		buf.append("<input type=\"reset\" value=\"Reset\" />");
		buf.append("</div>\n");
		buf.append("</form>");
		
		ctx.getPageMaker().makeTail(buf);
		
		this.writeReply(ctx, 200, "text/html", "OK", buf.toString());
	}
	
	public void handlePut(URI uri, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		StringBuffer buf = new StringBuffer();
		buf.append("ok!\n");
		buf.append(uri);
		this.writeReply(ctx, 200, "text/html", "OK", buf.toString());
	}
	
	public String supportedMethods() {
		return "GET, PUT";
	}
}