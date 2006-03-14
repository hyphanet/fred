package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import sun.net.dns.ResolverConfiguration.Options;

import freenet.client.HighLevelSimpleClient;
import freenet.config.Config;
import freenet.config.FilePersistentConfig;
import freenet.config.Option;
import freenet.config.SubConfig;
import freenet.support.Bucket;
import freenet.support.HTMLEncoder;
import freenet.support.MultiValueTable;
import freenet.support.SimpleFieldSet;
import freenet.node.Node;
import freenet.node.Version;
import freenet.pluginmanager.HTTPRequest;


// FIXME: add logging, comments
public class ConfigToadlet extends Toadlet {
	private Config config;
	
	ConfigToadlet(HighLevelSimpleClient client, Config conf, String CSSName) {
		super(client, CSSName);
		config=conf;
	}

	public void handleGet(URI uri, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		StringBuffer buf = new StringBuffer();
		SubConfig[] sc = config.getConfigs();
		
		HTTPRequest request = new HTTPRequest(uri);
		if (request.hasParameters() == false) {
			ctx.getPageMaker().makeHead(buf, "Freenet Node Configuration", CSSName);
			buf.append("<h1 class=\"title\">Node Configuration</h1>\n");
			buf.append("<div class=\"config\">\n");
			buf.append("	<ul class=\"config\">\n");
			// FIXME: Use POST insteed !
			buf.append("<form method=\"get\">");
			String last = null;
			
			for(int i=0; i<sc.length;i++){
				Option[] o = sc[i].getOptions();
				String prefix = new String(sc[i].getPrefix());
				
				if(last == null || ! last.equalsIgnoreCase(prefix)){
					buf.append("</p>\n");
					buf.append("</span>\n");
					buf.append("<span id=\""+prefix+"\">\n");
					buf.append("<p>\n");
				}
				
				for(int j=0; j<o.length; j++){
					String configName = new String(o[j].getName());
					/*
					if(prefix.equals("node") && configName.equals("name")){
						buf.append("<form method=\"post\"><input alt=\"node name\" class=\"config\"" +
								" type=\"text\" name=\"__node_name\" value=\""+o[j].getValueString()+"\"/></form>\n");
					}
					*/
						
					buf.append(o[j].getShortDesc()+":\n");
					buf.append("		<li>"+prefix+"."+configName+"=><input alt=\""+o[j].getShortDesc()+"\" class=\"config\"" +
							" type=\"text\" name=\""+prefix+"."+configName+"\" value=\""+o[j].getValueString()+"\"></li>\n");
				}
				
				buf.append("<br><hr>");
			}
			
			buf.append("<br>");
			buf.append("<input type=\"submit\" value=\"Apply\">");
			buf.append("<input type=\"reset\" value=\"Cancel\">");
			buf.append("</form>");
			buf.append("	</ul>\n");
			buf.append("</div>\n");
			
			ctx.getPageMaker().makeTail(buf);
			
			this.writeReply(ctx, 200, "text/html", "OK", buf.toString());
		
		} else {
			return;
//			for(int i=0; i<sc.length ; i++){
//				Option[] o = sc[i].getOptions();
//				String prefix = new String(sc[i].getPrefix());
//				String configName;
//				
//				for(int j=0; j<o.length; j++){
//					configName=o[j].getName();
//					
//					// we ignore unreconized parameters 
//					if(request.getParam(prefix+"."+configName) != ""){
//						if(o[j].getValueString() != request.getParam(prefix+"."+configName)){
//							try{
//								o[j].setValue(request.getParam(prefix+"."+configName));
//							}catch(Exception e){
//								buf.append(o[j].getName()+" "+e+"\n");
//							}
//						}
//					}
//				}
//			}
//			config.store();
//			writeReply(ctx, 200, "text/html", "OK", mkForwardPage(ctx, "Applying configuration", buf.toString(), "/config/", 10));
		}
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