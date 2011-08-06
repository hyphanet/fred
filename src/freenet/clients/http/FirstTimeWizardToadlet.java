/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.util.EnumMap;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.wizardsteps.*;
import freenet.config.Config;
import freenet.config.ConfigException;
import freenet.config.Option;
import freenet.l10n.NodeL10n;
import freenet.node.MasterKeysFileSizeException;
import freenet.node.MasterKeysWrongPasswordException;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.SecurityLevels;
import freenet.node.Node.AlreadySetPasswordException;
import freenet.node.SecurityLevels.NETWORK_THREAT_LEVEL;
import freenet.node.SecurityLevels.PHYSICAL_THREAT_LEVEL;
import freenet.pluginmanager.FredPluginBandwidthIndicator;
import freenet.support.Fields;
import freenet.support.HTMLNode;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.SizeUtil;
import freenet.support.Logger.LogLevel;
import freenet.support.api.HTTPRequest;
import freenet.support.io.FileUtil;

/**
 * A first time wizard aimed to ease the configuration of the node.
 *
 * TODO: a choose your CSS step?
 */
public class FirstTimeWizardToadlet extends Toadlet {
	private final NodeClientCore core;
	private final Config config;
	private final EnumMap<WIZARD_STEP, AbstractGetStep> getHandlers;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	public enum WIZARD_STEP {
		WELCOME,
		// Before security levels, because once the network security level has been set, we won't redirect
		// the user to the wizard page.
		BROWSER_WARNING,
		// We have to set up UPnP before reaching the bandwidth stage, so we can autodetect bandwidth settings.
		MISC,
		OPENNET,
		SECURITY_NETWORK,
		SECURITY_PHYSICAL,
		NAME_SELECTION,
		BANDWIDTH,
		DATASTORE_SIZE,
		CONGRATZ
	}

	FirstTimeWizardToadlet(HighLevelSimpleClient client, Node node, NodeClientCore core) {
		//Generic Toadlet-related initialization.
		super(client);
		this.core = core;
		this.config = node.config;

		//Add GET handlers for steps.
		getHandlers = new EnumMap<WIZARD_STEP, AbstractGetStep>(WIZARD_STEP.class);
		getHandlers.put(WIZARD_STEP.WELCOME, new GetWELCOME(config));
		getHandlers.put(WIZARD_STEP.BROWSER_WARNING, new GetBROWSER_WARNING());
		getHandlers.put(WIZARD_STEP.OPENNET, new GetOPENNET());
		getHandlers.put(WIZARD_STEP.SECURITY_NETWORK, new GetSECURITY_NETWORK());
		getHandlers.put(WIZARD_STEP.SECURITY_PHYSICAL, new GetSECURITY_PHYSICAL(core));
		getHandlers.put(WIZARD_STEP.NAME_SELECTION, new GetNAME_SELECTION());
		getHandlers.put(WIZARD_STEP.BANDWIDTH, new GetBANDWIDTH(core, config));
		getHandlers.put(WIZARD_STEP.DATASTORE_SIZE, new GetDATASTORE_SIZE(core, config));
		getHandlers.put(WIZARD_STEP.CONGRATZ, new GetCONGRATZ(config));
	}

	public static final String TOADLET_URL = "/wizard/";

	public void handleMethodGET(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, "Unauthorized", NodeL10n.getBase().getString("Toadlet.unauthorized"));
			return;
		}

		//Read the current step from the URL parameter, defaulting to the welcome page if unset.
		WIZARD_STEP currentStep = WIZARD_STEP.valueOf(request.getParam("step", WIZARD_STEP.WELCOME.toString()));
		//Get handler for page.
		AbstractGetStep getStep = getHandlers.get(currentStep);
		//Generate page to surround the content, using the step's title.
		PageNode pageNode = ctx.getPageMaker().getPageNode(getStep.TITLE_KEY, false, false, ctx);
		//Return the page to the browser.
		writeHTMLReply(ctx, 200, "OK", getStep.getPage(pageNode.content, request, ctx));
	}

	private String l10nSec(String key) {
		return NodeL10n.getBase().getString("SecurityLevels."+key);
	}

	private String l10nSec(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("SecurityLevels."+key, pattern, value);
	}

	public void handleMethodPOST(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {

		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, "Unauthorized", NodeL10n.getBase().getString("Toadlet.unauthorized"));
			return;
		}

		String passwd = request.getPartAsStringFailsafe("formPassword", 32);
		boolean noPassword = (passwd == null) || !passwd.equals(core.formPassword);
		if(noPassword) {
			if(logMINOR) Logger.minor(this, "No password ("+passwd+" should be "+core.formPassword+ ')');
			super.writeTemporaryRedirect(ctx, "invalid/unhandled data", "/");
			return;
		}

		if(request.isPartSet("networkSecurityF")) {
			// We don't require a confirmation here, since it's one page at a time, so there's less information to
			// confuse the user, and we don't know whether the node has friends yet etc.
			// FIXME should we have confirmation here???

			String networkThreatLevel = request.getPartAsStringFailsafe("security-levels.networkThreatLevel", 128);
			NETWORK_THREAT_LEVEL newThreatLevel = SecurityLevels.parseNetworkThreatLevel(networkThreatLevel);

			/*If the user didn't select a network security level before clicking continue or the selected
			* security level could not be determined, redirect to the same page.*/
			if(newThreatLevel == null || !request.isPartSet("security-levels.networkThreatLevel")) {
				//TODO: StringBuilder is not thread-safe but it's faster. Is it okay in this case?
				StringBuilder redirectTo = new StringBuilder(TOADLET_URL+"?step="+WIZARD_STEP.SECURITY_NETWORK+"&opennet=");
				//Max length of 5 because 5 letters in false, 4 in true.
				redirectTo.append(request.getPartAsStringFailsafe("opennet", 5));
				super.writeTemporaryRedirect(ctx, "step1", redirectTo.toString());
				return;
			}
			if((newThreatLevel == NETWORK_THREAT_LEVEL.MAXIMUM || newThreatLevel == NETWORK_THREAT_LEVEL.HIGH)) {
				if((!request.isPartSet("security-levels.networkThreatLevel.confirm")) &&
					(!request.isPartSet("security-levels.networkThreatLevel.tryConfirm"))) {
					PageNode page = ctx.getPageMaker().getPageNode(l10n("networkSecurityPageTitle"), false, false, ctx);
					HTMLNode pageNode = page.outer;
					HTMLNode content = page.content;

					HTMLNode infobox = content.addChild("div", "class", "infobox infobox-information");
					infobox.addChild("div", "class", "infobox-header", l10n("networkThreatLevelConfirmTitle."+newThreatLevel));
					HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
					HTMLNode formNode = ctx.addFormChild(infoboxContent, ".", "configFormSecLevels");
					formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "security-levels.networkThreatLevel", networkThreatLevel });
					if(newThreatLevel == NETWORK_THREAT_LEVEL.MAXIMUM) {
						HTMLNode p = formNode.addChild("p");
						NodeL10n.getBase().addL10nSubstitution(p, "SecurityLevels.maximumNetworkThreatLevelWarning", new String[] { "bold" }, new HTMLNode[] { HTMLNode.STRONG });
						p.addChild("#", " ");
						NodeL10n.getBase().addL10nSubstitution(p, "SecurityLevels.maxSecurityYouNeedFriends", new String[] { "bold" }, new HTMLNode[] { HTMLNode.STRONG });
						formNode.addChild("p").addChild("input", new String[] { "type", "name", "value" }, new String[] { "checkbox", "security-levels.networkThreatLevel.confirm", "off" }, l10nSec("maximumNetworkThreatLevelCheckbox"));
					} else /*if(newThreatLevel == NETWORK_THREAT_LEVEL.HIGH)*/ {
						HTMLNode p = formNode.addChild("p");
						NodeL10n.getBase().addL10nSubstitution(p, "FirstTimeWizardToadlet.highNetworkThreatLevelWarning", new String[] { "bold" }, new HTMLNode[] { HTMLNode.STRONG });
						formNode.addChild("p").addChild("input", new String[] { "type", "name", "value" }, new String[] { "checkbox", "security-levels.networkThreatLevel.confirm", "off" }, l10n("highNetworkThreatLevelCheckbox"));
					}
					formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "security-levels.networkThreatLevel.tryConfirm", "on" });
					formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "seclevels", "on" });
					formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "networkSecurityF", l10n("continue")});
					writeHTMLReply(ctx, 200, "OK", pageNode.generate());
					return;
				} else if((!request.isPartSet("security-levels.networkThreatLevel.confirm")) &&
						request.isPartSet("security-levels.networkThreatLevel.tryConfirm")) {
					super.writeTemporaryRedirect(ctx, "step1", TOADLET_URL+"?step="+WIZARD_STEP.SECURITY_NETWORK);
					return;
				}
			}
			core.node.securityLevels.setThreatLevel(newThreatLevel);
			core.storeConfig();
			super.writeTemporaryRedirect(ctx, "step1", TOADLET_URL+"?step="+WIZARD_STEP.SECURITY_PHYSICAL);
			return;
		} else if(request.isPartSet("physicalSecurityF")) {
			// We don't require a confirmation here, since it's one page at a time, so there's less information to
			// confuse the user, and we don't know whether the node has friends yet etc.
			// FIXME should we have confirmation here???

			String physicalThreatLevel = request.getPartAsStringFailsafe("security-levels.physicalThreatLevel", 128);
			PHYSICAL_THREAT_LEVEL oldThreatLevel = core.node.securityLevels.getPhysicalThreatLevel();
			PHYSICAL_THREAT_LEVEL newThreatLevel = SecurityLevels.parsePhysicalThreatLevel(physicalThreatLevel);
			if(logMINOR) Logger.minor(this, "Old threat level: "+oldThreatLevel+" new threat level: "+newThreatLevel);

			/*If the user didn't select a network security level before clicking continue or the selected
			* security level could not be determined, redirect to the same page.*/
			if(newThreatLevel == null || !request.isPartSet("security-levels.physicalThreatLevel")) {
				super.writeTemporaryRedirect(ctx, "step1", TOADLET_URL+"?step="+WIZARD_STEP.SECURITY_PHYSICAL);
				return;
			}
			if(newThreatLevel == PHYSICAL_THREAT_LEVEL.HIGH && oldThreatLevel != newThreatLevel) {
				// Check for password
				String pass = request.getPartAsStringFailsafe("masterPassword", SecurityLevelsToadlet.MAX_PASSWORD_LENGTH);
				if(pass != null && pass.length() > 0) {
					try {
						if(oldThreatLevel == PHYSICAL_THREAT_LEVEL.NORMAL || oldThreatLevel == PHYSICAL_THREAT_LEVEL.LOW)
							core.node.changeMasterPassword("", pass, true);
						else
							core.node.setMasterPassword(pass, true);
					} catch (AlreadySetPasswordException e) {
						// Do nothing, already set a password.
					} catch (MasterKeysWrongPasswordException e) {
						System.err.println("Wrong password!");
						PageNode page = ctx.getPageMaker().getPageNode(l10n("passwordPageTitle"), false, false, ctx);
						HTMLNode pageNode = page.outer;
						HTMLNode contentNode = page.content;

						HTMLNode content = ctx.getPageMaker().getInfobox("infobox-error",
								l10n("passwordWrongTitle"), contentNode, null, true).
								addChild("div", "class", "infobox-content");

						SecurityLevelsToadlet.generatePasswordFormPage(true, ctx.getContainer(), content, true, false, true, newThreatLevel.name(), null);

						addBackToPhysicalSeclevelsLink(content);

						writeHTMLReply(ctx, 200, "OK", pageNode.generate());
						return;
					} catch (MasterKeysFileSizeException e) {
						sendPasswordFileCorruptedPage(e.isTooBig(), ctx, false, true);
						return;
					}
				} else {
					// Must set a password!
					PageNode page = ctx.getPageMaker().getPageNode(l10n("passwordPageTitle"), false, false, ctx);
					HTMLNode pageNode = page.outer;
					HTMLNode contentNode = page.content;

					HTMLNode content = ctx.getPageMaker().getInfobox("infobox-error",
							l10nSec("enterPasswordTitle"), contentNode, null, true).
							addChild("div", "class", "infobox-content");

					if(pass != null && pass.length() == 0) {
						content.addChild("p", l10nSec("passwordNotZeroLength"));
					}

					SecurityLevelsToadlet.generatePasswordFormPage(false, ctx.getContainer(), content, true, false, true, newThreatLevel.name(), null);

					addBackToPhysicalSeclevelsLink(content);

					writeHTMLReply(ctx, 200, "OK", pageNode.generate());
					return;
				}
			}
			if((newThreatLevel == PHYSICAL_THREAT_LEVEL.LOW || newThreatLevel == PHYSICAL_THREAT_LEVEL.NORMAL) &&
					oldThreatLevel == PHYSICAL_THREAT_LEVEL.HIGH) {
				// Check for password
				String pass = request.getPartAsStringFailsafe("masterPassword", SecurityLevelsToadlet.MAX_PASSWORD_LENGTH);
				if(pass != null && pass.length() > 0) {
					// This is actually the OLD password ...
					try {
						core.node.changeMasterPassword(pass, "", true);
					} catch (IOException e) {
						if(!core.node.getMasterPasswordFile().exists()) {
							// Ok.
							System.out.println("Master password file no longer exists, assuming this is deliberate");
						} else {
							System.err.println("Cannot change password as cannot write new passwords file: "+e);
							e.printStackTrace();
							String msg = "<html><head><title>"+l10n("cantWriteNewMasterKeysFileTitle")+
									"</title></head><body><h1>"+l10n("cantWriteNewMasterKeysFileTitle")+"</h1><pre>";
							StringWriter sw = new StringWriter();
							PrintWriter pw = new PrintWriter(sw);
							e.printStackTrace(pw);
							pw.flush();
							msg = msg + sw.toString() + "</pre></body></html>";
							writeHTMLReply(ctx, 500, "Internal Error", msg);
							return;
						}
					} catch (MasterKeysWrongPasswordException e) {
						System.err.println("Wrong password!");
						PageNode page = ctx.getPageMaker().getPageNode(l10n("passwordForDecryptTitle"), false, false, ctx);
						HTMLNode pageNode = page.outer;
						HTMLNode contentNode = page.content;

						HTMLNode content = ctx.getPageMaker().getInfobox("infobox-error",
								l10n("passwordWrongTitle"), contentNode, null, true).
								addChild("div", "class", "infobox-content");

						SecurityLevelsToadlet.generatePasswordFormPage(true, ctx.getContainer(), content, true, false, false, newThreatLevel.name(), null);

						addBackToPhysicalSeclevelsLink(content);

						writeHTMLReply(ctx, 200, "OK", pageNode.generate());
						return;
					} catch (MasterKeysFileSizeException e) {
						sendPasswordFileCorruptedPage(e.isTooBig(), ctx, false, true);
						return;
					} catch (AlreadySetPasswordException e) {
						System.err.println("Already set a password when changing it - maybe master.keys copied in at the wrong moment???");
					}
				} else if(core.node.getMasterPasswordFile().exists()) {
					// We need the old password
					PageNode page = ctx.getPageMaker().getPageNode(l10n("passwordForDecryptTitle"), false, false, ctx);
					HTMLNode pageNode = page.outer;
					HTMLNode contentNode = page.content;

					HTMLNode content = ctx.getPageMaker().getInfobox("infobox-error",
							l10nSec("passwordForDecryptTitle"), contentNode, null, true).
							addChild("div", "class", "infobox-content");

					if(pass != null && pass.length() == 0) {
						content.addChild("p", l10nSec("passwordNotZeroLength"));
					}

					SecurityLevelsToadlet.generatePasswordFormPage(false, ctx.getContainer(), content, true, true, false, newThreatLevel.name(), null);

					addBackToPhysicalSeclevelsLink(content);

					writeHTMLReply(ctx, 200, "OK", pageNode.generate());
					return;

				}

			}
			if(newThreatLevel == PHYSICAL_THREAT_LEVEL.MAXIMUM) {
				try {
					core.node.killMasterKeysFile();
				} catch (IOException e) {
					sendCantDeleteMasterKeysFile(ctx, newThreatLevel.name());
					return;
				}
			}
			core.node.securityLevels.setThreatLevel(newThreatLevel);
			core.storeConfig();
			try {
				core.node.lateSetupDatabase(null);
			} catch (MasterKeysWrongPasswordException e) {
				// Ignore, impossible???
				System.err.println("Failed starting up database while switching physical security level to "+newThreatLevel+" from "+oldThreatLevel+" : wrong password - this is impossible, it should have been handled by the other cases, suggest you remove master.keys");
			} catch (MasterKeysFileSizeException e) {
				System.err.println("Failed starting up database while switching physical security level to "+newThreatLevel+" from "+oldThreatLevel+" : "+core.node.getMasterPasswordFile()+" is too " + e.sizeToString());
			}
			if (core.node.isOpennetEnabled()) {
				super.writeTemporaryRedirect(ctx, "step1", TOADLET_URL+"?step="+WIZARD_STEP.NAME_SELECTION+"&opennet=true");
			} else {
				super.writeTemporaryRedirect(ctx, "step3", TOADLET_URL+"?step="+WIZARD_STEP.BANDWIDTH);
			}
			return;
		} else if(request.isPartSet("nnameF")) {
			String selectedNName = request.getPartAsStringFailsafe("nname", 128);
			try {
				config.get("node").set("name", selectedNName);
				Logger.normal(this, "The node name has been set to "+ selectedNName);
			} catch (ConfigException e) {
				Logger.error(this, "Should not happen, please report!" + e, e);
			}
			super.writeTemporaryRedirect(ctx, "step3", TOADLET_URL+"?step="+WIZARD_STEP.BANDWIDTH);
			return;
		} else if(request.isPartSet("bwF")) {
			_setUpstreamBandwidthLimit(request.getPartAsStringFailsafe("bw", 20)); // drop down options may be 6 chars or less, but formatted ones e.g. old value if re-running can be more
			super.writeTemporaryRedirect(ctx, "step4", TOADLET_URL+"?step="+WIZARD_STEP.DATASTORE_SIZE);
			return;
		} else if(request.isPartSet("dsF")) {
			_setDatastoreSize(request.getPartAsStringFailsafe("ds", 20)); // drop down options may be 6 chars or less, but formatted ones e.g. old value if re-running can be more
			super.writeTemporaryRedirect(ctx, "step5", TOADLET_URL+"?step="+WIZARD_STEP.CONGRATZ);
			return;
		} else if(request.isPartSet("miscF")) {
			try {
				config.get("node.updater").set("autoupdate", Boolean.parseBoolean(request.getPartAsStringFailsafe("autodeploy", 10)));
			} catch (ConfigException e) {
				Logger.error(this, "Should not happen, please report!" + e, e);
			}
			final boolean enableUPnP = request.isPartSet("upnp");
			if(enableUPnP != core.node.pluginManager.isPluginLoaded("plugins.UPnP.UPnP")) {
					// We can probably get connected without it, so don't force HTTPS.
					// We'd have to ask the user anyway...
					core.node.executor.execute(new Runnable() {

						private final boolean enable = enableUPnP;

						@Override
						public void run() {
							if(enable)
								core.node.pluginManager.startPluginOfficial("UPnP", true, false, false);
							else
								core.node.pluginManager.killPluginByClass("plugins.UPnP.UPnP", 5000);
						}

					});
			}
			super.writeTemporaryRedirect(ctx, "step7", TOADLET_URL+"?step="+WIZARD_STEP.OPENNET);
			return;

		}

		//The user changed their language on the welcome page. Change the language and rerender the page.
		if (request.isPartSet("l10n")) {
			String desiredLanguage = request.getPartAsStringFailsafe("l10n", 4096);
			try {
				config.get("node").set("l10n", desiredLanguage);
			} catch (freenet.config.InvalidConfigValueException e) {
				Logger.error(this, "Failed to set language to "+desiredLanguage+". "+e);
			} catch (freenet.config.NodeNeedRestartException e) {
				//Changing language doesn't require a restart, at least as of version 1385.
				//Doing so would be really annoying as the node would have to start up again
				//which could be very slow.
			}
		}

		super.writeTemporaryRedirect(ctx, "invalid/unhandled data", TOADLET_URL);
	}

	private void _setUpstreamBandwidthLimit(String selectedUploadSpeed) {
		try {
			config.get("node").set("outputBandwidthLimit", selectedUploadSpeed);
			Logger.normal(this, "The outputBandwidthLimit has been set to " + selectedUploadSpeed);
		} catch (ConfigException e) {
			Logger.error(this, "Should not happen, please report!" + e, e);
		}
	}

	private void sendPasswordFileCorruptedPage(boolean tooBig, ToadletContext ctx, boolean forSecLevels, boolean forFirstTimeWizard) throws ToadletContextClosedException, IOException {
		writeHTMLReply(ctx, 500, "OK", SecurityLevelsToadlet.sendPasswordFileCorruptedPageInner(tooBig, ctx, forSecLevels, forFirstTimeWizard, core.node.getMasterPasswordFile().getPath(), core.node).generate());
	}

	private void addBackToPhysicalSeclevelsLink(HTMLNode content) {
		content.addChild("a", "href", TOADLET_URL+"?step="+WIZARD_STEP.SECURITY_PHYSICAL, l10n("backToSecurityLevels"));
	}

	private String l10n(String key) {
		return NodeL10n.getBase().getString("FirstTimeWizardToadlet."+key);
	}
	
	private String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("FirstTimeWizardToadlet."+key, pattern, value);
	}

	private void _setDatastoreSize(String selectedStoreSize) {
		try {
			long size = Fields.parseLong(selectedStoreSize);
			// client cache: 10% up to 200MB
			long clientCacheSize = Math.min(size / 10, 200*1024*1024);
			// recent requests cache / slashdot cache / ULPR cache
			int upstreamLimit = config.get("node").getInt("outputBandwidthLimit");
			int downstreamLimit = config.get("node").getInt("inputBandwidthLimit");
			// is used for remote stuff, so go by the minimum of the two
			int limit;
			if(downstreamLimit <= 0) limit = upstreamLimit;
			else limit = Math.min(downstreamLimit, upstreamLimit);
			// 35KB/sec limit has been seen to have 0.5 store writes per second.
			// So saying we want to have space to cache everything is only doubling that ...
			// OTOH most stuff is at low enough HTL to go to the datastore and thus not to
			// the slashdot cache, so we could probably cut this significantly...
			long lifetime = config.get("node").getLong("slashdotCacheLifetime");
			long maxSlashdotCacheSize = (lifetime / 1000) * limit;
			long slashdotCacheSize = Math.min(size / 10, maxSlashdotCacheSize);

			long storeSize = size - (clientCacheSize + slashdotCacheSize);

			System.out.println("Setting datastore size to "+Fields.longToString(storeSize, true));
			config.get("node").set("storeSize", Fields.longToString(storeSize, true));
			if(config.get("node").getString("storeType").equals("ram"))
				config.get("node").set("storeType", "salt-hash");
			System.out.println("Setting client cache size to "+Fields.longToString(clientCacheSize, true));
			config.get("node").set("clientCacheSize", Fields.longToString(clientCacheSize, true));
			if(config.get("node").getString("clientCacheType").equals("ram"))
				config.get("node").set("clientCacheType", "salt-hash");
			System.out.println("Setting slashdot/ULPR/recent requests cache size to "+Fields.longToString(slashdotCacheSize, true));
			config.get("node").set("slashdotCacheSize", Fields.longToString(slashdotCacheSize, true));


			Logger.normal(this, "The storeSize has been set to " + selectedStoreSize);
		} catch(ConfigException e) {
			Logger.error(this, "Should not happen, please report!" + e, e);
		}
	}

	private void sendCantDeleteMasterKeysFile(ToadletContext ctx, String physicalSecurityLevel) throws ToadletContextClosedException, IOException {
		HTMLNode pageNode = SecurityLevelsToadlet.sendCantDeleteMasterKeysFileInner(ctx, core.node.getMasterPasswordFile().getPath(), false, physicalSecurityLevel, this.core.node);
		writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}

	@Override
	public String path() {
		return TOADLET_URL;
	}
}
