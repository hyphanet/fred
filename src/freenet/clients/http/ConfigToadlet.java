/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;

import freenet.client.HighLevelSimpleClient;
import freenet.config.BooleanOption;
import freenet.config.Config;
import freenet.config.ConfigCallback;
import freenet.config.EnumerableOptionCallback;
import freenet.config.InvalidConfigValueException;
import freenet.config.NodeNeedRestartException;
import freenet.config.Option;
import freenet.config.SubConfig;
import freenet.config.WrapperConfig;
import freenet.l10n.L10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.SecurityLevels;
import freenet.node.SecurityLevels.FRIENDS_THREAT_LEVEL;
import freenet.node.SecurityLevels.NETWORK_THREAT_LEVEL;
import freenet.node.SecurityLevels.PHYSICAL_THREAT_LEVEL;
import freenet.node.useralerts.AbstractUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.api.BooleanCallback;
import freenet.support.api.HTTPRequest;


// FIXME: add logging, comments
public class ConfigToadlet extends Toadlet {
	// If a setting has to be more than a meg, something is seriously wrong!
	private static final int MAX_PARAM_VALUE_SIZE = 1024*1024;
	private final Config config;
	private final NodeClientCore core;
	private final Node node;
	private boolean needRestart = false;
	private NeedRestartUserAlert needRestartUserAlert;

	private class NeedRestartUserAlert extends AbstractUserAlert {
		@Override
		public String getTitle() {
			return l10n("needRestartTitle");
		}

		@Override
		public String getText() {
			return getHTMLText().toString();
		}

		@Override
		public String getShortText() {
			return l10n("needRestartShort");
		}

		@Override
		public HTMLNode getHTMLText() {
			HTMLNode alertNode = new HTMLNode("div");
			alertNode.addChild("#", l10n("needRestart"));

			if (node.isUsingWrapper()) {
				alertNode.addChild("br");
				HTMLNode restartForm = alertNode.addChild("form", //
						new String[] { "action", "method" },//
				        new String[] { "/", "get" });
				restartForm.addChild("div");
				restartForm.addChild("input",//
						new String[] { "type", "name" },//
						new String[] { "hidden", "restart" });
				restartForm.addChild("input", //
						new String[] { "type", "name", "value" },//
						new String[] { "submit", "restart2",//
				                l10n("restartNode") });
			}

			return alertNode;
		}

		@Override
		public short getPriorityClass() {
			return UserAlert.WARNING;
		}

		@Override
		public boolean isValid() {
			return needRestart;
		}

		@Override
		public boolean userCanDismiss() {
			return false;
		}
	}

	ConfigToadlet(HighLevelSimpleClient client, Config conf, Node node, NodeClientCore core) {
		super(client);
		config=conf;
		this.core = core;
		this.node = node;
	}

	
	@Override
    public void handlePost(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		String pass = request.getPartAsString("formPassword", 32);
		if((pass == null) || !pass.equals(core.formPassword)) {
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/config/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		}
		
		if(request.isPartSet("seclevels")) {
			// Handle the security level changes.
			
			HTMLNode pageNode = null;
			HTMLNode content = null;
			HTMLNode ul = null;
			HTMLNode formNode = null;
			boolean addedWarning = false;
			String configName = "security-levels.networkThreatLevel";
			String confirm = "security-levels.networkThreatLevel.confirm";
			String tryConfirm = "security-levels.networkThreatLevel.tryConfirm";
			String networkThreatLevel = request.getPartAsString(configName, 128);
			NETWORK_THREAT_LEVEL newThreatLevel = SecurityLevels.parseNetworkThreatLevel(networkThreatLevel);
			if(newThreatLevel != null) {
				if(newThreatLevel != node.securityLevels.getNetworkThreatLevel()) {
					if(!request.isPartSet(confirm) && !request.isPartSet(tryConfirm)) {
						HTMLNode warning = node.securityLevels.getConfirmWarning(newThreatLevel, confirm);
						if(warning != null) {
							if(pageNode == null) {
								pageNode = ctx.getPageMaker().getPageNode(L10n.getString("ConfigToadlet.fullTitle", new String[] { "name" }, new String[] { node.getMyName() }), ctx);
								content = ctx.getPageMaker().getContentNode(pageNode);
								formNode = ctx.addFormChild(content, ".", "configFormSecLevels");
								ul = formNode.addChild("ul", "class", "config");
							}
							HTMLNode seclevelGroup = ul.addChild("li");

							seclevelGroup.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", configName, networkThreatLevel });
							HTMLNode infobox = seclevelGroup.addChild("div", "class", "infobox infobox-information");
							infobox.addChild("div", "class", "infobox-header", l10nSec("networkThreatLevelConfirmTitle", "mode", SecurityLevels.localisedName(newThreatLevel)));
							HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
							infoboxContent.addChild(warning);
							infoboxContent.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", tryConfirm, "on" });
							addedWarning = true;
						} else {
							// Apply immediately, no confirm needed.
							node.securityLevels.setThreatLevel(newThreatLevel);
						}
					} else if(request.isPartSet(confirm)) {
						// Apply immediately, user confirmed it.
						node.securityLevels.setThreatLevel(newThreatLevel);
					}
				}
			}
			
			configName = "security-levels.friendsThreatLevel";
			confirm = "security-levels.friendsThreatLevel.confirm";
			tryConfirm = "security-levels.friendsThreatLevel.tryConfirm";
			String friendsThreatLevel = request.getPartAsString(configName, 128);
			FRIENDS_THREAT_LEVEL newFriendsLevel = SecurityLevels.parseFriendsThreatLevel(friendsThreatLevel);
			if(newFriendsLevel != null) {
				if(newFriendsLevel != node.securityLevels.getFriendsThreatLevel()) {
					if(!request.isPartSet(confirm) && !request.isPartSet(tryConfirm)) {
						HTMLNode warning = node.securityLevels.getConfirmWarning(newFriendsLevel, confirm);
						if(warning != null) {
							if(pageNode == null) {
								pageNode = ctx.getPageMaker().getPageNode(L10n.getString("ConfigToadlet.fullTitle", new String[] { "name" }, new String[] { node.getMyName() }), ctx);
								content = ctx.getPageMaker().getContentNode(pageNode);
								formNode = ctx.addFormChild(content, ".", "configFormSecLevels");
								ul = formNode.addChild("ul", "class", "config");
							}
							HTMLNode seclevelGroup = ul.addChild("li");

							seclevelGroup.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", configName, friendsThreatLevel });
							HTMLNode infobox = seclevelGroup.addChild("div", "class", "infobox infobox-information");
							infobox.addChild("div", "class", "infobox-header", l10nSec("friendsThreatLevelConfirmTitle", "mode", SecurityLevels.localisedName(newThreatLevel)));
							HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
							infoboxContent.addChild(warning);
							infoboxContent.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", tryConfirm, "on" });
							addedWarning = true;
						} else {
							// Apply immediately, no confirm needed.
							node.securityLevels.setThreatLevel(newFriendsLevel);
						}
					} else if(request.isPartSet(confirm)) {
						// Apply immediately, user confirmed it.
						node.securityLevels.setThreatLevel(newFriendsLevel);
					}
				}
			}
			
			configName = "security-levels.physicalThreatLevel";
			confirm = "security-levels.physicalThreatLevel.confirm";
			tryConfirm = "security-levels.physicalThreatLevel.tryConfirm";
			String physicalThreatLevel = request.getPartAsString(configName, 128);
			PHYSICAL_THREAT_LEVEL newPhysicalLevel = SecurityLevels.parsePhysicalThreatLevel(physicalThreatLevel);
			if(newPhysicalLevel != null) {
				if(newPhysicalLevel != node.securityLevels.getPhysicalThreatLevel()) {
					// No confirmation for changes to physical threat level.
					node.securityLevels.setThreatLevel(newPhysicalLevel);
				}
			}
			
			if(pageNode != null) {
				formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "seclevels", "on" });
				formNode.addChild("input", new String[] { "type", "value" }, new String[] { "submit", l10n("apply")});
				formNode.addChild("input", new String[] { "type", "value" }, new String[] { "reset",  l10n("reset")});
				writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				return;
			} else {
				MultiValueTable headers = new MultiValueTable();
				headers.put("Location", "/config/?mode="+MODE_SECURITY_LEVELS);
				ctx.sendReplyHeaders(302, "Found", headers, null, 0);
				return;
			}
		}
		
		SubConfig[] sc = config.getConfigs();
		StringBuffer errbuf = new StringBuffer();
		
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, L10n.getString("Toadlet.unauthorizedTitle"), L10n.getString("Toadlet.unauthorized"));
			return;
		}
		
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		
		for(int i=0; i<sc.length ; i++){
			Option<?>[] o = sc[i].getOptions();
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
						} catch (InvalidConfigValueException e) {
							errbuf.append(o[j].getName()).append(' ').append(e.getMessage()).append('\n');
						} catch (NodeNeedRestartException e) {
							needRestart = true;
						} catch (Exception e){
                            errbuf.append(o[j].getName()).append(' ').append(e).append('\n');
							Logger.error(this, "Caught "+e, e);
						}
					}
				}
			}
			
			// Wrapper params
			String wrapperConfigName = "wrapper.java.maxmemory";
			if(request.isPartSet(wrapperConfigName)) {
				String value = request.getPartAsString(wrapperConfigName, MAX_PARAM_VALUE_SIZE);
				if(!WrapperConfig.getWrapperProperty(wrapperConfigName).equals(value)) {
					if(logMINOR) Logger.minor(this, "Setting "+wrapperConfigName+" to "+value);
					WrapperConfig.setWrapperProperty(wrapperConfigName, value);
				}
			}
		}
		core.storeConfig();
		
		HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("appliedTitle"), ctx);
		HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
		
		if (errbuf.length() == 0) {
			HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-success", l10n("appliedTitle")));
			HTMLNode content = ctx.getPageMaker().getContentNode(infobox);
			content.addChild("#", l10n("appliedSuccess"));
			
			if (needRestart) {
				content.addChild("br");
				content.addChild("#", l10n("needRestart"));

				if (node.isUsingWrapper()) {
					content.addChild("br");
					HTMLNode restartForm = content.addChild("form",//
					        new String[] { "action", "method" }, new String[] { "/", "get" }//
					        ).addChild("div");
					restartForm.addChild("input",//
					        new String[] { "type", "name" },//
					        new String[] { "hidden", "restart" });
					restartForm.addChild("input", //
					        new String[] { "type", "name", "value" },//
					        new String[] { "submit", "restart2",//
					                l10n("restartNode") });
				}
				
				if (needRestartUserAlert == null) {
					needRestartUserAlert = new NeedRestartUserAlert();
					node.clientCore.alerts.register(needRestartUserAlert);
				}
			}
		} else {
			HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-error", l10n("appliedFailureTitle")));
			HTMLNode content = ctx.getPageMaker().getContentNode(infobox).addChild("div", "class", "infobox-content");
			content.addChild("#", l10n("appliedFailureExceptions"));
			content.addChild("br");
			content.addChild("#", errbuf.toString());
		}
		
		HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-normal", l10n("possibilitiesTitle")));
		HTMLNode content = ctx.getPageMaker().getContentNode(infobox);
		content.addChild("a", new String[]{"href", "title"}, new String[]{".", l10n("shortTitle")}, l10n("returnToNodeConfig"));
		content.addChild("br");
		addHomepageLink(content);

		writeHTMLReply(ctx, 200, "OK", pageNode.generate());
		
	}
	
	private static final String l10n(String string) {
		return L10n.getString("ConfigToadlet." + string);
	}

	public static final int MODE_SECURITY_LEVELS = 3;
	
	@Override
    public void handleGet(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, L10n.getString("Toadlet.unauthorizedTitle"), L10n.getString("Toadlet.unauthorized"));
			return;
		}
		
		SubConfig[] sc = config.getConfigs();
		Arrays.sort(sc);
		
		HTMLNode pageNode = ctx.getPageMaker().getPageNode(L10n.getString("ConfigToadlet.fullTitle", new String[] { "name" }, new String[] { node.getMyName() }), ctx);
		HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
		
		contentNode.addChild(core.alerts.createSummary());
		
		final int mode = ctx.getPageMaker().drawModeSelectionArray(core, req, contentNode, MODE_SECURITY_LEVELS, "SecurityLevels.title", "SecurityLevels.tooltip");
		
		if(mode == MODE_SECURITY_LEVELS) {
			drawSecurityLevelsPage(contentNode, ctx);
		} else {
		
		if(mode >= PageMaker.MODE_ADVANCED){
			HTMLNode navigationBar = ctx.getPageMaker().getInfobox("navbar", l10n("configNavTitle"));
			HTMLNode navigationContent = ctx.getPageMaker().getContentNode(navigationBar).addChild("ul");
			if(!L10n.getSelectedLanguage().equals(L10n.LANGUAGE.getDefault()))
				navigationContent.addChild("a", "href", TranslationToadlet.TOADLET_URL, l10n("contributeTranslation"));
			HTMLNode navigationTable = navigationContent.addChild("table", "class", "config_navigation");
			HTMLNode navigationTableRow = navigationTable.addChild("tr");
			HTMLNode nextTableCell = navigationTableRow;
			
			for(int i=0; i<sc.length;i++){
				if(sc[i].getPrefix().equals("security-levels")) continue;
				nextTableCell.addChild("td", "class", "config_navigation").addChild("li").addChild("a", "href", '#' +sc[i].getPrefix(), l10n(sc[i].getPrefix()));
			}
			contentNode.addChild(navigationBar);
		}

		HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		infobox.addChild("div", "class", "infobox-header", l10n("title"));
		HTMLNode configNode = infobox.addChild("div", "class", "infobox-content");
		HTMLNode formNode = ctx.addFormChild(configNode, ".", "configForm");
		
		if(WrapperConfig.canChangeProperties()) {
			formNode.addChild("div", "class", "configprefix", l10n("wrapper"));
			HTMLNode list = formNode.addChild("ul", "class", "config");
			HTMLNode item = list.addChild("li");
			String configName = "wrapper.java.maxmemory";
			// FIXME how to get the real default???
			String defaultValue = "128";
			String curValue = WrapperConfig.getWrapperProperty(configName);
			item.addChild("span", new String[]{ "class", "title", "style" },
					new String[]{ "configshortdesc", L10n.getString("ConfigToadlet.defaultIs", new String[] { "default" }, new String[] { defaultValue }),
					"cursor: help;" }).addChild(L10n.getHTMLNode("WrapperConfig."+configName+".short"));
			item.addChild("span", "class", "config").addChild("input", new String[] { "type", "class", "name", "value" }, new String[] { "text", "config", configName, curValue });
			item.addChild("span", "class", "configlongdesc").addChild(L10n.getHTMLNode("WrapperConfig."+configName+".long"));
		}
		
		for(int i=0; i<sc.length;i++){
			short displayedConfigElements = 0;
			
			if(sc[i].getPrefix().equals("security-levels")) continue;
			Option<?>[] o = sc[i].getOptions();
			HTMLNode configGroupUlNode = new HTMLNode("ul", "class", "config");
			
			for(int j=0; j<o.length; j++){
				if(! (mode == PageMaker.MODE_SIMPLE && o[j].isExpert())){
					displayedConfigElements++;
					String configName = o[j].getName();
					
					HTMLNode configItemNode = configGroupUlNode.addChild("li");
					configItemNode.addChild("span", new String[]{ "class", "title", "style" },
							new String[]{ "configshortdesc", L10n.getString("ConfigToadlet.defaultIs", new String[] { "default" }, new String[] { o[j].getDefault() }) + (mode >= PageMaker.MODE_ADVANCED ? " ["+sc[i].getPrefix() + '.' + o[j].getName() + ']' : ""),
							"cursor: help;" }).addChild(L10n.getHTMLNode(o[j].getShortDesc()));
					HTMLNode configItemValueNode = configItemNode.addChild("span", "class", "config");
					if(o[j].getValueString() == null){
						Logger.error(this, sc[i].getPrefix() + configName + "has returned null from config!);");
						continue;
					}
					
					ConfigCallback<?> callback = o[j].getCallback();
					if(callback instanceof EnumerableOptionCallback)
						configItemValueNode.addChild(addComboBox((EnumerableOptionCallback) callback, sc[i],
						        configName, callback.isReadOnly()));
					else if(callback instanceof BooleanCallback)
						configItemValueNode.addChild(addBooleanComboBox(((BooleanOption) o[j]).getValue(), sc[i],
						        configName, callback.isReadOnly()));
					else if (callback.isReadOnly())
						configItemValueNode.addChild("input", //
						        new String[] { "type", "class", "disabled", "alt", "name", "value" }, //
						        new String[] { "text", "config", "disabled", o[j].getShortDesc(),
						                sc[i].getPrefix() + '.' + configName, o[j].getValueString() });
					else
						configItemValueNode.addChild("input",//
						        new String[] { "type", "class", "alt", "name", "value" }, //
						        new String[] { "text", "config", o[j].getShortDesc(),
						                sc[i].getPrefix() + '.' + configName, o[j].getValueString() });

					configItemNode.addChild("span", "class", "configlongdesc").addChild(L10n.getHTMLNode(o[j].getLongDesc()));
				}
			}
			
			if(displayedConfigElements>0) {
				formNode.addChild("div", "class", "configprefix", l10n(sc[i].getPrefix()));
				formNode.addChild("a", "id", sc[i].getPrefix());
				formNode.addChild(configGroupUlNode);
			}
		}
		
		formNode.addChild("input", new String[] { "type", "value" }, new String[] { "submit", l10n("apply")});
		formNode.addChild("input", new String[] { "type", "value" }, new String[] { "reset",  l10n("reset")});
		
		}
		
		this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}
	
	private void drawSecurityLevelsPage(HTMLNode contentNode, ToadletContext ctx) {
		HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		infobox.addChild("div", "class", "infobox-header", l10nSec("title"));
		HTMLNode configNode = infobox.addChild("div", "class", "infobox-content");
		HTMLNode formNode = ctx.addFormChild(configNode, ".", "configFormSecLevels");
		// Network security level
		formNode.addChild("div", "class", "configprefix", l10nSec("networkThreatLevelShort"));
		HTMLNode ul = formNode.addChild("ul", "class", "config");
		HTMLNode seclevelGroup = ul.addChild("li");
		seclevelGroup.addChild("#", l10nSec("networkThreatLevel"));
		
		NETWORK_THREAT_LEVEL networkLevel = node.securityLevels.getNetworkThreatLevel();
		
		String controlName = "security-levels.networkThreatLevel";
		for(NETWORK_THREAT_LEVEL level : NETWORK_THREAT_LEVEL.values()) {
			HTMLNode input;
			if(level == networkLevel) {
				input = seclevelGroup.addChild("p").addChild("input", new String[] { "type", "checked", "name", "value" }, new String[] { "radio", "on", controlName, level.name() });
			} else {
				input = seclevelGroup.addChild("p").addChild("input", new String[] { "type", "name", "value" }, new String[] { "radio", controlName, level.name() });
			}
			input.addChild("b", l10nSec("networkThreatLevel.name."+level));
			input.addChild("#", ": ");
			L10n.addL10nSubstitution(input, "SecurityLevels.networkThreatLevel.choice."+level, new String[] { "bold", "/bold" }, new String[] { "<b>", "</b>" });
			HTMLNode inner = input.addChild("p").addChild("i");
			L10n.addL10nSubstitution(inner, "SecurityLevels.networkThreatLevel.desc."+level, new String[] { "bold", "/bold" }, new String[] { "<b>", "</b>" });
		}
		
		// Friends security level
		formNode.addChild("div", "class", "configprefix", l10nSec("friendsThreatLevelShort"));
		ul = formNode.addChild("ul", "class", "config");
		seclevelGroup = ul.addChild("li");
		seclevelGroup.addChild("#", l10nSec("friendsThreatLevel"));
		
		FRIENDS_THREAT_LEVEL friendsLevel = node.securityLevels.getFriendsThreatLevel();
		
		controlName = "security-levels.friendsThreatLevel";
		for(FRIENDS_THREAT_LEVEL level : FRIENDS_THREAT_LEVEL.values()) {
			HTMLNode input;
			if(level == friendsLevel) {
				input = seclevelGroup.addChild("p").addChild("input", new String[] { "type", "checked", "name", "value" }, new String[] { "radio", "on", controlName, level.name() });
			} else {
				input = seclevelGroup.addChild("p").addChild("input", new String[] { "type", "name", "value" }, new String[] { "radio", controlName, level.name() });
			}
			input.addChild("b", l10nSec("friendsThreatLevel.name."+level));
			input.addChild("#", ": ");
			L10n.addL10nSubstitution(input, "SecurityLevels.friendsThreatLevel.choice."+level, new String[] { "bold", "/bold" }, new String[] { "<b>", "</b>" });
			HTMLNode inner = input.addChild("p").addChild("i");
			L10n.addL10nSubstitution(inner, "SecurityLevels.friendsThreatLevel.desc."+level, new String[] { "bold", "/bold" }, new String[] { "<b>", "</b>" });
		}
		
		// Physical security level
		formNode.addChild("div", "class", "configprefix", l10nSec("physicalThreatLevelShort"));
		ul = formNode.addChild("ul", "class", "config");
		seclevelGroup = ul.addChild("li");
		seclevelGroup.addChild("#", l10nSec("physicalThreatLevel"));
		
		PHYSICAL_THREAT_LEVEL physicalLevel = node.securityLevels.getPhysicalThreatLevel();
		
		controlName = "security-levels.physicalThreatLevel";
		for(PHYSICAL_THREAT_LEVEL level : PHYSICAL_THREAT_LEVEL.values()) {
			HTMLNode input;
			if(level == physicalLevel) {
				input = seclevelGroup.addChild("p").addChild("input", new String[] { "type", "checked", "name", "value" }, new String[] { "radio", "on", controlName, level.name() });
			} else {
				input = seclevelGroup.addChild("p").addChild("input", new String[] { "type", "name", "value" }, new String[] { "radio", controlName, level.name() });
			}
			input.addChild("b", l10nSec("physicalThreatLevel.name."+level));
			input.addChild("#", ": ");
			L10n.addL10nSubstitution(input, "SecurityLevels.physicalThreatLevel.choice."+level, new String[] { "bold", "/bold" }, new String[] { "<b>", "</b>" });
			HTMLNode inner = input.addChild("p").addChild("i");
			L10n.addL10nSubstitution(inner, "SecurityLevels.physicalThreatLevel.desc."+level, new String[] { "bold", "/bold" }, new String[] { "<b>", "</b>" });
		}
		
		
		
		// FIXME implement the rest, it should be very similar to the above.
		
		formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "seclevels", "on" });
		formNode.addChild("input", new String[] { "type", "value" }, new String[] { "submit", l10n("apply")});
		formNode.addChild("input", new String[] { "type", "value" }, new String[] { "reset",  l10n("reset")});
	}


	private String l10nSec(String key) {
		return L10n.getString("SecurityLevels."+key);
	}
	
	private String l10nSec(String key, String pattern, String value) {
		return L10n.getString("SecurityLevels."+key, pattern, value);
	}

	@Override
    public String supportedMethods() {
		return "GET, POST";
	}
	
	private HTMLNode addComboBox(EnumerableOptionCallback o, SubConfig sc, String name, boolean disabled) {
		HTMLNode result;
		if (disabled)
			result = new HTMLNode("select", //
			        new String[] { "name", "disabled" }, //
			        new String[] { sc.getPrefix() + '.' + name, "disabled" });
		else
			result = new HTMLNode("select", "name", sc.getPrefix() + '.' + name);
		
		String[] possibleValues = o.getPossibleValues();
		for(int i=0; i<possibleValues.length; i++) {
			if(possibleValues[i].equals(o.get()))
				result.addChild("option", new String[] { "value", "selected" }, new String[] { possibleValues[i], "selected" }, possibleValues[i]);
			else
				result.addChild("option", "value", possibleValues[i], possibleValues[i]);
		}
		
		return result;
	}
	
	private HTMLNode addBooleanComboBox(boolean value, SubConfig sc, String name, boolean disabled) {
		HTMLNode result;
		if (disabled)
			result = new HTMLNode("select", //
			        new String[] { "name", "disabled" }, //
			        new String[] { sc.getPrefix() + '.' + name, "disabled" });
		else
			result = new HTMLNode("select", "name", sc.getPrefix() + '.' + name);

		if (value) {
			result.addChild("option", new String[] { "value", "selected" }, new String[] { "true", "selected" },
			        l10n("true"));
			result.addChild("option", "value", "false", l10n("false"));
		} else {
			result.addChild("option", "value", "true", l10n("true"));
			result.addChild("option", new String[] { "value", "selected" }, new String[] { "false", "selected" },
			        l10n("false"));
		}
		
		return result;
	}
}
