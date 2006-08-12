package freenet.clients.http;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import freenet.client.HighLevelSimpleClient;
import freenet.config.Config;
import freenet.config.Option;
import freenet.config.SubConfig;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.io.Bucket;
import freenet.support.io.BucketTools;


// FIXME: add logging, comments
public class ConfigToadlet extends Toadlet {
	private Config config;
	private final NodeClientCore core;
	private final Node node;
	
	ConfigToadlet(HighLevelSimpleClient client, Config conf, Node node, NodeClientCore core) {
		super(client);
		config=conf;
		this.core = core;
		this.node = node;
	}

	public void handlePost(URI uri, Bucket data, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		StringBuffer errbuf = new StringBuffer();
		SubConfig[] sc = config.getConfigs();
		
		// FIXME this is stupid, use a direct constructor
		
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
		
		String pass = request.getParam("formPassword");
		if((pass == null) || !pass.equals(core.formPassword)) {
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/config/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
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
				if(request.isParameterSet(prefix+"."+configName)) {
					if(!(o[j].getValueString().equals(request.getParam(prefix+"."+configName)))){
						Logger.minor(this, "Setting "+prefix+"."+configName+" to "+request.getParam(prefix+"."+configName));
						try{
							o[j].setValue(request.getParam(prefix+"."+configName));
						}catch(Exception e){
							errbuf.append(o[j].getName()+" "+e+"\n");
							Logger.error(this, "Caught "+e, e);
						}
					}
				}
			}
		}
		config.store();
		
		HTMLNode pageNode = ctx.getPageMaker().getPageNode("Configuration Applied");
		HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
		
		if (errbuf.length() == 0) {
			HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-success", "Configuration Applied"));
			ctx.getPageMaker().getContentNode(infobox).addChild("#", "Your configuration changes were applied successfully.");
		} else {
			HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-error", "Configuration Not Applied"));
			HTMLNode content = infobox.addChild("div", "class", "infobox-content");
			content.addChild("#", "Your configuration changes were applied with the following exceptions:");
			content.addChild("br");
			content.addChild("#", errbuf.toString());
		}
		
		HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-normal", "Your Possibilities"));
		HTMLNode content = ctx.getPageMaker().getContentNode(infobox);
		content.addChild("a", new String[]{"href", "title"}, new String[]{".", "Configuration"}, "Return to node configuration");
		content.addChild("br");
		content.addChild("a", new String[]{"href", "title"}, new String[]{"/", "Node homepage"}, "Return to node homepage");

		writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
		
	}
	
	public void handleGet(URI uri, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		SubConfig[] sc = config.getConfigs();
		boolean advancedEnabled = core.isAdvancedDarknetEnabled();
		
		HTMLNode pageNode = ctx.getPageMaker().getPageNode("Freenet Node Configuration of " + node.getMyName());
		HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);

		HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		infobox.addChild("div", "class", "infobox-header", "Freenet node configuration");
		HTMLNode configNode = infobox.addChild("div", "class", "infobox-content");
		HTMLNode formNode = configNode.addChild("form", new String[] { "action", "method" }, new String[] { ".", "post" });
		formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", core.formPassword });
		
		for(int i=0; i<sc.length;i++){
			short displayedConfigElements = 0;
			
			Option[] o = sc[i].getOptions();
			HTMLNode configGroupUlNode = new HTMLNode("ul", "class", "config");
			
			for(int j=0; j<o.length; j++){
				if(! (!advancedEnabled && o[j].isExpert())){
					displayedConfigElements++;
					String configName = o[j].getName();
					
					HTMLNode configItemNode = configGroupUlNode.addChild("li");
					configItemNode.addChild("span", "class", "configshortdesc", o[j].getShortDesc());
					HTMLNode configItemValueNode = configItemNode.addChild("span", "class", "config");
					
					if(o[j].getValueString().equals("true") || o[j].getValueString().equals("false")){
						HTMLNode selectNode = configItemValueNode.addChild("select", "name", sc[i].getPrefix() + "." + configName);
						if(o[j].getValueString().equals("true")){
							selectNode.addChild("option", new String[] { "value", "selected" }, new String[] { "true", "selected" }, "true");
							selectNode.addChild("option", "value", "false", "false");
						}else{
							selectNode.addChild("option", "value", "true", "true");
							selectNode.addChild("option", new String[] { "value", "selected" }, new String[] { "false", "selected" }, "false");
						}
					}else{
						configItemValueNode.addChild("input", new String[] { "type", "class", "alt", "name", "value" }, new String[] { "text", "config", o[j].getShortDesc(), sc[i].getPrefix() + "." + configName, o[j].getValueString() });
					}
					configItemNode.addChild("span", "class", "configlongdesc", o[j].getLongDesc());
				}
			}
			
			if(displayedConfigElements>0) {
				formNode.addChild("div", "class", "configprefix", sc[i].getPrefix());
				formNode.addChild(configGroupUlNode);
			}
		}
		
		formNode.addChild("input", new String[] { "type", "value" }, new String[] { "submit", "Apply" });
		formNode.addChild("input", new String[] { "type", "value" }, new String[] { "reset", "Reset" });
		
		StringBuffer pageBuffer = new StringBuffer();
		pageNode.generate(pageBuffer);
		this.writeReply(ctx, 200, "text/html", "OK", pageBuffer.toString());
	}
	
	public String supportedMethods() {
		return "GET, POST";
	}
}
