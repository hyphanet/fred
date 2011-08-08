package freenet.clients.http.wizardsteps;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.*;
import freenet.l10n.NodeL10n;
import freenet.node.*;
import freenet.support.HTMLNode;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Allows the user to set the physical security level.
 */
public class SECURITY_PHYSICAL extends Toadlet implements Step {

	private final NodeClientCore core;

	/**
	 * Constructs a new SECURITY_PHYSICAL GET handler.
	 * @param core used to check the current security level.
	 * @param client used for Toadlet constructor.
	 */
	public SECURITY_PHYSICAL(NodeClientCore core, HighLevelSimpleClient client) {
		super(client);
		this.core = core;
	}

	public String path() {
		return FirstTimeWizardToadlet.TOADLET_URL+"?step=SECURITY_PHYSICAL";
	}

	@Override
	public String getTitleKey() {
		return "physicalSecurityPageTitle";
	}

	@Override
	public void getStep(HTMLNode contentNode, HTTPRequest request, ToadletContext ctx) {
		HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		HTMLNode infoboxHeader = infobox.addChild("div", "class", "infobox-header");
		HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");

		infoboxHeader.addChild("#", WizardL10n.l10nSec("physicalThreatLevelShort"));
		infoboxContent.addChild("p", WizardL10n.l10nSec("physicalThreatLevel"));
		HTMLNode form = ctx.addFormChild(infoboxContent, ".", "physicalSecurityForm");
		HTMLNode div = form.addChild("div", "class", "opennetDiv");
		String controlName = "security-levels.physicalThreatLevel";
		HTMLNode swapWarning = div.addChild("p").addChild("i");
		NodeL10n.getBase().addL10nSubstitution(swapWarning, "SecurityLevels.physicalThreatLevelSwapfile",
			new String[]{"bold", "truecrypt"},
			new HTMLNode[]{HTMLNode.STRONG,
				HTMLNode.linkInNewWindow(ExternalLinkToadlet.escape("http://www.truecrypt.org/"))});
		if(File.separatorChar == '\\') {
			swapWarning.addChild("#", " " + WizardL10n.l10nSec("physicalThreatLevelSwapfileWindows"));
		}
		for(SecurityLevels.PHYSICAL_THREAT_LEVEL level : SecurityLevels.PHYSICAL_THREAT_LEVEL.values()) {
			HTMLNode input;
			input = div.addChild("p").addChild("input", new String[] { "type", "name", "value" }, new String[] { "radio", controlName, level.name() });
			input.addChild("b", WizardL10n.l10nSec("physicalThreatLevel.name." + level));
			input.addChild("#", ": ");
			NodeL10n.getBase().addL10nSubstitution(input, "SecurityLevels.physicalThreatLevel.choice."+level, new String[] { "bold" }, new HTMLNode[] { HTMLNode.STRONG });
			if(level == SecurityLevels.PHYSICAL_THREAT_LEVEL.HIGH) {
				if(core.node.securityLevels.getPhysicalThreatLevel() != level) {
					// Add password form
					HTMLNode p = div.addChild("p");
					p.addChild("label", "for", "passwordBox", WizardL10n.l10nSec("setPasswordLabel")+":");
					p.addChild("input", new String[] { "id", "type", "name" }, new String[] { "passwordBox", "password", "masterPassword" });
				}
			}
		}
		div.addChild("#", WizardL10n.l10nSec("physicalThreatLevelEnd"));
		//Marker for step on POST side
		form.addChild("input",
		        new String [] { "type", "name", "value" },
		        new String [] { "hidden", "step", "SECURITY_PHYSICAL" });
		form.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "physicalSecurityF", WizardL10n.l10n("continue")});
		form.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "cancel", NodeL10n.getBase().getString("Toadlet.cancel")});
	}

	@Override
	public String postStep(HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		// We don't require a confirmation here, since it's one page at a time, so there's less information to
		// confuse the user, and we don't know whether the node has friends yet etc.
		// FIXME should we have confirmation here???

		String physicalThreatLevel = request.getPartAsStringFailsafe("security-levels.physicalThreatLevel", 128);
		SecurityLevels.PHYSICAL_THREAT_LEVEL oldThreatLevel = core.node.securityLevels.getPhysicalThreatLevel();
		SecurityLevels.PHYSICAL_THREAT_LEVEL newThreatLevel = SecurityLevels.parsePhysicalThreatLevel(physicalThreatLevel);
		if(FirstTimeWizardToadlet.shouldLogMinor()) Logger.minor(this, "Old threat level: " + oldThreatLevel + " new threat level: " + newThreatLevel);

		/*If the user didn't select a network security level before clicking continue or the selected
		* security level could not be determined, redirect to the same page.*/
		if(newThreatLevel == null || !request.isPartSet("security-levels.physicalThreatLevel")) {
			return FirstTimeWizardToadlet.TOADLET_URL+"?step="+FirstTimeWizardToadlet.WIZARD_STEP.SECURITY_PHYSICAL;
		}
		if(newThreatLevel == SecurityLevels.PHYSICAL_THREAT_LEVEL.HIGH && oldThreatLevel != newThreatLevel) {
			// Check for password
			String pass = request.getPartAsStringFailsafe("masterPassword", SecurityLevelsToadlet.MAX_PASSWORD_LENGTH);
			if(pass != null && pass.length() > 0) {
				try {
					if(oldThreatLevel == SecurityLevels.PHYSICAL_THREAT_LEVEL.NORMAL || oldThreatLevel == SecurityLevels.PHYSICAL_THREAT_LEVEL.LOW)
						core.node.changeMasterPassword("", pass, true);
					else
						core.node.setMasterPassword(pass, true);
				} catch (Node.AlreadySetPasswordException e) {
					// Do nothing, already set a password.
				} catch (MasterKeysWrongPasswordException e) {
					System.err.println("Wrong password!");
					PageNode page = ctx.getPageMaker().getPageNode(WizardL10n.l10n("passwordPageTitle"), false, false, ctx);
					HTMLNode pageNode = page.outer;
					HTMLNode contentNode = page.content;

					HTMLNode content = ctx.getPageMaker().getInfobox("infobox-error",
							WizardL10n.l10n("passwordWrongTitle"), contentNode, null, true).
							addChild("div", "class", "infobox-content");

					SecurityLevelsToadlet.generatePasswordFormPage(true, ctx.getContainer(), content, true, false, true, newThreatLevel.name(), null);

					addBackToPhysicalSeclevelsLink(content);

					writeHTMLReply(ctx, 200, "OK", pageNode.generate());
					return null;
				} catch (MasterKeysFileSizeException e) {
					sendPasswordFileCorruptedPage(e.isTooBig(), ctx, false, true);
					return null;
				}
			} else {
				// Must set a password!
				PageNode page = ctx.getPageMaker().getPageNode(WizardL10n.l10n("passwordPageTitle"), false, false, ctx);
				HTMLNode pageNode = page.outer;
				HTMLNode contentNode = page.content;

				HTMLNode content = ctx.getPageMaker().getInfobox("infobox-error",
						WizardL10n.l10nSec("enterPasswordTitle"), contentNode, null, true).
						addChild("div", "class", "infobox-content");

				if(pass != null && pass.length() == 0) {
					content.addChild("p", WizardL10n.l10nSec("passwordNotZeroLength"));
				}

				SecurityLevelsToadlet.generatePasswordFormPage(false, ctx.getContainer(), content, true, false, true, newThreatLevel.name(), null);

				addBackToPhysicalSeclevelsLink(content);

				writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				return null;
			}
		}
		if((newThreatLevel == SecurityLevels.PHYSICAL_THREAT_LEVEL.LOW || newThreatLevel == SecurityLevels.PHYSICAL_THREAT_LEVEL.NORMAL) &&
				oldThreatLevel == SecurityLevels.PHYSICAL_THREAT_LEVEL.HIGH) {
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
						String msg = "<html><head><title>"+WizardL10n.l10n("cantWriteNewMasterKeysFileTitle")+
								"</title></head><body><h1>"+WizardL10n.l10n("cantWriteNewMasterKeysFileTitle")+"</h1><pre>";
						StringWriter sw = new StringWriter();
						PrintWriter pw = new PrintWriter(sw);
						e.printStackTrace(pw);
						pw.flush();
						msg = msg + sw.toString() + "</pre></body></html>";
						writeHTMLReply(ctx, 500, "Internal Error", msg);
						return null;
					}
				} catch (MasterKeysWrongPasswordException e) {
					System.err.println("Wrong password!");
					PageNode page = ctx.getPageMaker().getPageNode(WizardL10n.l10n("passwordForDecryptTitle"), false, false, ctx);
					HTMLNode pageNode = page.outer;
					HTMLNode contentNode = page.content;

					HTMLNode content = ctx.getPageMaker().getInfobox("infobox-error",
							WizardL10n.l10n("passwordWrongTitle"), contentNode, null, true).
							addChild("div", "class", "infobox-content");

					SecurityLevelsToadlet.generatePasswordFormPage(true, ctx.getContainer(), content, true, false, false, newThreatLevel.name(), null);

					addBackToPhysicalSeclevelsLink(content);

					writeHTMLReply(ctx, 200, "OK", pageNode.generate());
					return null;
				} catch (MasterKeysFileSizeException e) {
					sendPasswordFileCorruptedPage(e.isTooBig(), ctx, false, true);
					return null;
				} catch (Node.AlreadySetPasswordException e) {
					System.err.println("Already set a password when changing it - maybe master.keys copied in at the wrong moment???");
				}
			} else if(core.node.getMasterPasswordFile().exists()) {
				// We need the old password
				PageNode page = ctx.getPageMaker().getPageNode(WizardL10n.l10n("passwordForDecryptTitle"), false, false, ctx);
				HTMLNode pageNode = page.outer;
				HTMLNode contentNode = page.content;

				HTMLNode content = ctx.getPageMaker().getInfobox("infobox-error",
						WizardL10n.l10nSec("passwordForDecryptTitle"), contentNode, null, true).
						addChild("div", "class", "infobox-content");

				if(pass != null && pass.length() == 0) {
					content.addChild("p", WizardL10n.l10nSec("passwordNotZeroLength"));
				}

				SecurityLevelsToadlet.generatePasswordFormPage(false, ctx.getContainer(), content, true, true, false, newThreatLevel.name(), null);

				addBackToPhysicalSeclevelsLink(content);

				writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				return null;

			}

		}
		if(newThreatLevel == SecurityLevels.PHYSICAL_THREAT_LEVEL.MAXIMUM) {
			try {
				core.node.killMasterKeysFile();
			} catch (IOException e) {
				sendCantDeleteMasterKeysFile(ctx, newThreatLevel.name());
				return null;
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
			return FirstTimeWizardToadlet.TOADLET_URL+"?step="+FirstTimeWizardToadlet.WIZARD_STEP.NAME_SELECTION+"&opennet=true";
		} else {
			return FirstTimeWizardToadlet.TOADLET_URL+"?step="+FirstTimeWizardToadlet.WIZARD_STEP.BANDWIDTH;
		}
	}

	private void sendPasswordFileCorruptedPage(boolean tooBig, ToadletContext ctx, boolean forSecLevels, boolean forFirstTimeWizard) throws ToadletContextClosedException, IOException {
		writeHTMLReply(ctx, 500, "OK", SecurityLevelsToadlet.sendPasswordFileCorruptedPageInner(tooBig, ctx, forSecLevels, forFirstTimeWizard, core.node.getMasterPasswordFile().getPath(), core.node).generate());
	}

	private void sendCantDeleteMasterKeysFile(ToadletContext ctx, String physicalSecurityLevel) throws ToadletContextClosedException, IOException {
		HTMLNode pageNode = SecurityLevelsToadlet.sendCantDeleteMasterKeysFileInner(ctx, core.node.getMasterPasswordFile().getPath(), false, physicalSecurityLevel, this.core.node);
		writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}

	private void addBackToPhysicalSeclevelsLink(HTMLNode content) {
		content.addChild("a", "href", FirstTimeWizardToadlet.TOADLET_URL+"?step="+FirstTimeWizardToadlet.WIZARD_STEP.SECURITY_PHYSICAL, WizardL10n.l10n("backToSecurityLevels"));
	}
}
