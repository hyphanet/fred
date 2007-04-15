/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;

import freenet.client.HighLevelSimpleClient;
import freenet.config.Config;
import freenet.config.EnumerableOption;
import freenet.config.Option;
import freenet.config.SubConfig;
import freenet.l10n.L10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.api.HTTPRequest;


// FIXME: add logging, comments
public class ConfigToadlet extends Toadlet {
	// If a setting has to be more than a meg, something is seriously wrong!
	private static final int MAX_PARAM_VALUE_SIZE = 1024*1024;
	private final Config config;
	private final NodeClientCore core;
	private final Node node;
	
	ConfigToadlet(HighLevelSimpleClient client, Config conf, Node node, NodeClientCore core) {
		super(client);
		config=conf;
		this.core = core;
		this.node = node;
	}

	public void handlePost(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		StringBuffer errbuf = new StringBuffer();
		SubConfig[] sc = config.getConfigs();
		
		String pass = request.getPartAsString("formPassword", 32);
		if((pass == null) || !pass.equals(core.formPassword)) {
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/config/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		}
		
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, "Unauthorized", "You are not permitted access to this page");
			return;
		}
		
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		
		for(int i=0; i<sc.length ; i++){
			Option[] o = sc[i].getOptions();
			String prefix = sc[i].getPrefix();
			String configName;
			
			for(int j=0; j<o.length; j++){
				configName=o[j].getName();
				if(logMINOR) Logger.minor(this, "Setting "+prefix+ '.' +configName);
				
				// we ignore unreconized parameters 
				if(request.isPartSet(prefix+ '.' +configName)) {
					String value = request.getPartAsString(prefix+ '.' +configName, MAX_PARAM_VALUE_SIZE);
					if(!(o[j].getValueString().equals(value))){
						if(logMINOR) Logger.minor(this, "Setting "+prefix+ '.' +configName+" to "+value);
						try{
							o[j].setValue(value);
						}catch(Exception e){
                            errbuf.append(o[j].getName()).append(' ').append(e).append('\n');
							Logger.error(this, "Caught "+e, e);
						}
					}
				}
			}
		}
		core.storeConfig();
		
		HTMLNode pageNode = ctx.getPageMaker().getPageNode("Configuration Applied", ctx);
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
	
	public void handleGet(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, "Unauthorized", "You are not permitted access to this page");
			return;
		}
		
		SubConfig[] sc = config.getConfigs();
		Arrays.sort(sc);
		boolean advancedModeEnabled = core.isAdvancedModeEnabled();
		
		HTMLNode pageNode = ctx.getPageMaker().getPageNode("Freenet Node Configuration of " + node.getMyName(), ctx);
		HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
		
		if(ctx.isAllowedFullAccess())
			contentNode.addChild(core.alerts.createSummary());
		if(advancedModeEnabled){
			HTMLNode navigationBar = ctx.getPageMaker().getInfobox("navbar", "Configuration Navigation");
			HTMLNode navigationContent = ctx.getPageMaker().getContentNode(navigationBar).addChild("ul");
			for(int i=0; i<sc.length;i++){
				navigationContent.addChild("li").addChild("a", "href", '#' +sc[i].getPrefix(), sc[i].getPrefix());
			}
			contentNode.addChild(navigationBar);
		}

		HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		infobox.addChild("div", "class", "infobox-header", "Freenet node configuration");
		HTMLNode configNode = infobox.addChild("div", "class", "infobox-content");
		HTMLNode formNode = ctx.addFormChild(configNode, ".", "configForm");
		
		for(int i=0; i<sc.length;i++){
			short displayedConfigElements = 0;
			
			Option[] o = sc[i].getOptions();
			HTMLNode configGroupUlNode = new HTMLNode("ul", "class", "config");
			
			for(int j=0; j<o.length; j++){
				if(! (!advancedModeEnabled && o[j].isExpert())){
					displayedConfigElements++;
					String configName = o[j].getName();
					
					HTMLNode configItemNode = configGroupUlNode.addChild("li");
					configItemNode.addChild("span", new String[]{ "class", "title", "style" },
							new String[]{ "configshortdesc", "The default for that configuration option is : '" +
							o[j].getDefault() + '\'', "cursor: help;" }).addChild(L10n.getHTMLNode(o[j].getShortDesc()));
					HTMLNode configItemValueNode = configItemNode.addChild("span", "class", "config");
					if(o[j].getValueString() == null){
						Logger.error(this, sc[i].getPrefix() + configName + "has returned null from config!);");
						continue; 
					}
					
					if((o[j] instanceof EnumerableOption) && (o[j].isEnumerable()))
						configItemValueNode.addChild(addComboBox((EnumerableOption)o[j], sc[i], configName));
					else
						configItemValueNode.addChild("input", new String[] { "type", "class", "alt", "name", "value" }, new String[] { "text", "config", o[j].getShortDesc(), sc[i].getPrefix() + '.' + configName, o[j].getValueString() });

					configItemNode.addChild("span", "class", "configlongdesc").addChild(L10n.getHTMLNode(o[j].getLongDesc()));
				}
			}
			
			if(displayedConfigElements>0) {
				formNode.addChild("div", "class", "configprefix", sc[i].getPrefix());
				formNode.addChild("a", "name", sc[i].getPrefix());
				formNode.addChild(configGroupUlNode);
			}
		}
		
		formNode.addChild("input", new String[] { "type", "value" }, new String[] { "submit", "Apply" });
		formNode.addChild("input", new String[] { "type", "value" }, new String[] { "reset", "Reset" });
		
		this.writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
	}
	
	public String supportedMethods() {
		return "GET, POST";
	}
	
	private HTMLNode addComboBox(EnumerableOption o, SubConfig sc, String name) {
		HTMLNode result = new HTMLNode("select", "name", sc.getPrefix() + '.' + name);
		String[] possibleValues = o.getPossibleValues();
		for(int i=0; i<possibleValues.length; i++) {
			if(possibleValues[i].equals(o.getValueString()))
				result.addChild("option", new String[] { "value", "selected" }, new String[] { possibleValues[i], "selected" }, possibleValues[i]);
			else
				result.addChild("option", "value", possibleValues[i], possibleValues[i]);
		}
		
		return result;
	}
}
