/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;

import freenet.client.HighLevelSimpleClient;
import freenet.config.BooleanOption;
import freenet.config.Config;
import freenet.config.ConfigCallback;
import freenet.config.EnumerableOptionCallback;
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
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.api.BooleanCallback;
import freenet.support.api.HTTPRequest;

/**
 * The security levels page.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 *
 */
public class SecurityLevelsToadlet extends Toadlet {

	private final NodeClientCore core;
	private final Node node;

	SecurityLevelsToadlet(HighLevelSimpleClient client, Node node, NodeClientCore core) {
		super(client);
		this.core = core;
		this.node = node;
	}
	
	@Override
    public void handlePost(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		if (!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, L10n.getString("Toadlet.unauthorizedTitle"), L10n
			        .getString("Toadlet.unauthorized"));
			return;
		}
		
		String pass = request.getPartAsString("formPassword", 32);
		if((pass == null) || !pass.equals(core.formPassword)) {
			MultiValueTable<String,String> headers = new MultiValueTable<String,String>();
			headers.put("Location", "/seclevels/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		}
		
		if(request.isPartSet("seclevels")) {
			// Handle the security level changes.
			HTMLNode pageNode = null;
			HTMLNode content = null;
			HTMLNode ul = null;
			HTMLNode formNode = null;
			boolean changedAnything = false;
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
								PageNode page = ctx.getPageMaker().getPageNode(L10n.getString("ConfigToadlet.fullTitle", new String[] { "name" }, new String[] { node.getMyName() }), ctx);
								pageNode = page.outer;
								content = page.content;
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
						} else {
							// Apply immediately, no confirm needed.
							node.securityLevels.setThreatLevel(newThreatLevel);
							changedAnything = true;
						}
					} else if(request.isPartSet(confirm)) {
						// Apply immediately, user confirmed it.
						node.securityLevels.setThreatLevel(newThreatLevel);
						changedAnything = true;
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
								PageNode page = ctx.getPageMaker().getPageNode(L10n.getString("ConfigToadlet.fullTitle", new String[] { "name" }, new String[] { node.getMyName() }), ctx);
								pageNode = page.outer;
								content = page.content;
								formNode = ctx.addFormChild(content, ".", "configFormSecLevels");
								ul = formNode.addChild("ul", "class", "config");
							}
							HTMLNode seclevelGroup = ul.addChild("li");

							seclevelGroup.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", configName, friendsThreatLevel });
							HTMLNode infobox = seclevelGroup.addChild("div", "class", "infobox infobox-information");
							infobox.addChild("div", "class", "infobox-header", l10nSec("friendsThreatLevelConfirmTitle", "mode", SecurityLevels.localisedName(newFriendsLevel)));
							HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
							infoboxContent.addChild(warning);
							infoboxContent.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", tryConfirm, "on" });
						} else {
							// Apply immediately, no confirm needed.
							node.securityLevels.setThreatLevel(newFriendsLevel);
							changedAnything = true;
						}
					} else if(request.isPartSet(confirm)) {
						// Apply immediately, user confirmed it.
						node.securityLevels.setThreatLevel(newFriendsLevel);
						changedAnything = true;
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
					changedAnything = true;
				}
			}
			
			if(changedAnything)
				core.storeConfig();
			
			if(pageNode != null) {
				formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "seclevels", "on" });
				formNode.addChild("input", new String[] { "type", "value" }, new String[] { "submit", l10n("apply")});
				formNode.addChild("input", new String[] { "type", "value" }, new String[] { "reset",  l10n("reset")});
				writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				return;
			} else {
				MultiValueTable<String, String> headers = new MultiValueTable<String, String>();
				headers.put("Location", "/seclevels/");
				ctx.sendReplyHeaders(302, "Found", headers, null, 0);
				return;
			}
		} else {
			try {
				throw new RedirectException("/seclevels/");
			} catch (URISyntaxException e) {
				// Impossible
			}
		}
	}
	
	@Override
    public void handleGet(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, L10n.getString("Toadlet.unauthorizedTitle"), L10n.getString("Toadlet.unauthorized"));
			return;
		}
		
		PageNode page = ctx.getPageMaker().getPageNode(L10n.getString("SecurityLevelsToadlet.fullTitle", new String[] { "name" }, new String[] { node.getMyName() }), ctx);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;
		
		contentNode.addChild(core.alerts.createSummary());
		
		drawSecurityLevelsPage(contentNode, ctx);
		
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

	@Override
	public String path() {
		return "/seclevels/";
	}

	@Override
	public String supportedMethods() {
		return "GET, POST";
	}

	private static final String l10n(String string) {
		return L10n.getString("ConfigToadlet." + string);
	}

	private String l10nSec(String key) {
		return L10n.getString("SecurityLevels."+key);
	}
	
	private String l10nSec(String key, String pattern, String value) {
		return L10n.getString("SecurityLevels."+key, pattern, value);
	}


}
