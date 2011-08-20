package freenet.clients.http.wizardsteps;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.ExternalLinkToadlet;
import freenet.clients.http.FirstTimeWizardToadlet;
import freenet.clients.http.PageNode;
import freenet.clients.http.SecurityLevelsToadlet;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.l10n.NodeL10n;
import freenet.node.MasterKeysFileSizeException;
import freenet.node.MasterKeysWrongPasswordException;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.SecurityLevels;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Allows the user to set the physical security level.
 */
public class SECURITY_PHYSICAL implements Step {

	private final NodeClientCore core;

	private enum PASSWORD_PROMPT {
		WRONG,
		DECRYPT,
		NEW
	}

	/**
	 * Constructs a new SECURITY_PHYSICAL GET handler.
	 * @param core used to check or set the current security level and password.
	 */
	public SECURITY_PHYSICAL(NodeClientCore core) {
		this.core = core;
	}

	@Override
	public void getStep(HTTPRequest request, StepPageHelper helper) {

		if (request.isParameterSet("error")) {
			if (errorHandler(request, helper)) {
				//Error page generated successfully.
				return;
			}
			//Problem generating error page; generate default.
		}

		HTMLNode contentNode = helper.getPageContent(WizardL10n.l10n("physicalSecurityPageTitle"));
		HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		HTMLNode infoboxHeader = infobox.addChild("div", "class", "infobox-header");
		HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");

		infoboxHeader.addChild("#", WizardL10n.l10nSec("physicalThreatLevelShort"));
		infoboxContent.addChild("p", WizardL10n.l10nSec("physicalThreatLevel"));
		HTMLNode form = helper.addFormChild(infoboxContent, ".", "physicalSecurityForm");
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
			input = div.addChild("p").addChild("input",
			        new String[] { "type", "name", "value" },
			        new String[] { "radio", controlName, level.name() });
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

	/**
	 * Internal error handler wrapper with the hope of making the code more readable.
	 * @param request defines which error and information about it
	 * @param helper creates page, infoboxes, forms.
	 * @return whether an error page was successfully generated.
	 */
	private boolean errorHandler(HTTPRequest request, StepPageHelper helper) {
		String physicalThreatLevel = request.getPartAsStringFailsafe("newThreatLevel", 128);
		SecurityLevels.PHYSICAL_THREAT_LEVEL newThreatLevel = SecurityLevels.parsePhysicalThreatLevel(physicalThreatLevel);
		String error = request.getParam("error");

		if (error.equals("pass")) {
			//Password prompt requested
			PASSWORD_PROMPT type;
			try {
				type = PASSWORD_PROMPT.valueOf(request.getParam("type"));
			}  catch (IllegalArgumentException e) {
				//Render the default page if unable to parse password prompt type.
				return false;
			}

			String pageTitleKey;
			String infoBoxTitleKey;

			switch (type) {
				case WRONG:
					pageTitleKey = "passwordPageTitle";
					infoBoxTitleKey = "passwordWrongTitle";
					break;
				case NEW:
					pageTitleKey = "passwordPageTitle";
					infoBoxTitleKey = "enterPasswordTitle";
					break;
				case DECRYPT:
					pageTitleKey = "passwordForDecryptTitle";
					infoBoxTitleKey = "passwordForDecryptTitle";
					break;
				default:
					//Unanticipated value for type!
					return false;
			}

			HTMLNode contentNode = helper.getPageContent(WizardL10n.l10n(pageTitleKey));

			HTMLNode content = helper.getInfobox("infobox-error",
				WizardL10n.l10n(infoBoxTitleKey), contentNode, null, true).
				addChild("div", "class", "infobox-content");

			HTMLNode form = helper.addFormChild(content, FirstTimeWizardToadlet.TOADLET_URL+"?step=SECURITY_PHYSICAL", "masterPasswordForm");

			SecurityLevelsToadlet.generatePasswordFormPage(true, form, content, true, false, newThreatLevel.name(), null);

			addBackToPhysicalSeclevelsLink(content);
			return true;
		} else if (error.equals("corrupt")) {
			//Password file corrupt
			SecurityLevelsToadlet.sendPasswordFileCorruptedPageInner(helper, core.node.getMasterPasswordFile().getPath());
			return true;
		} else if (error.equals("delete")) {
			SecurityLevelsToadlet.sendCantDeleteMasterKeysFileInner(helper, core.node.getMasterPasswordFile().getPath(), newThreatLevel.name());
			return true;
		}

		//Error type was not recognized.
		return false;
	}

	@Override
	public String postStep(HTTPRequest request) throws IOException {
		final String errorCorrupt = FirstTimeWizardToadlet.TOADLET_URL+"?step=SECURITY_PHYSICAL&error=corrupt";
		String pass = request.getPartAsStringFailsafe("masterPassword", SecurityLevelsToadlet.MAX_PASSWORD_LENGTH);
		final boolean passwordIsBlank = pass != null && pass.length() == 0;

		String physicalThreatLevel = request.getPartAsStringFailsafe("security-levels.physicalThreatLevel", 128);
		SecurityLevels.PHYSICAL_THREAT_LEVEL oldThreatLevel = core.node.securityLevels.getPhysicalThreatLevel();
		SecurityLevels.PHYSICAL_THREAT_LEVEL newThreatLevel = SecurityLevels.parsePhysicalThreatLevel(physicalThreatLevel);
		if (FirstTimeWizardToadlet.shouldLogMinor()) {
			Logger.minor(this, "Old threat level: " + oldThreatLevel + " new threat level: " + newThreatLevel);
		}

		/*If the user didn't select a network security level before clicking continue or the selected
		* security level could not be determined, redirect to the same page.*/
		if (newThreatLevel == null || !request.isPartSet("security-levels.physicalThreatLevel")) {
			return FirstTimeWizardToadlet.TOADLET_URL+"?step="+FirstTimeWizardToadlet.WIZARD_STEP.SECURITY_PHYSICAL;
		}
		//Changing to high physical threat level: set password.
		if (newThreatLevel == SecurityLevels.PHYSICAL_THREAT_LEVEL.HIGH && oldThreatLevel != newThreatLevel) {
			if(passwordIsBlank) {
				try {
					if(oldThreatLevel == SecurityLevels.PHYSICAL_THREAT_LEVEL.NORMAL ||
					        oldThreatLevel == SecurityLevels.PHYSICAL_THREAT_LEVEL.LOW) {
						core.node.changeMasterPassword("", pass, true);
					} else {
						core.node.setMasterPassword(pass, true);
					}
				} catch (Node.AlreadySetPasswordException e) {
					// Do nothing, already set a password.
				} catch (MasterKeysWrongPasswordException e) {
					return promptPassword(newThreatLevel, PASSWORD_PROMPT.WRONG, false);
				} catch (MasterKeysFileSizeException e) {
					return errorCorrupt;
				}
			} else {
				// Must set a password!
				return promptPassword(newThreatLevel, PASSWORD_PROMPT.NEW, !passwordIsBlank);
			}
		}
		//Decreasing to low or normal from high: remove password.
		if ((newThreatLevel == SecurityLevels.PHYSICAL_THREAT_LEVEL.LOW || newThreatLevel == SecurityLevels.PHYSICAL_THREAT_LEVEL.NORMAL) &&
			        oldThreatLevel == SecurityLevels.PHYSICAL_THREAT_LEVEL.HIGH) {
			if (passwordIsBlank) {
				//Password was specified when decreasing the security level, so it's the old password intended to decrypt.
				try {
					core.node.changeMasterPassword(pass, "", true);
				} catch (IOException e) {
					if(!core.node.getMasterPasswordFile().exists()) {
						// Ok.
						System.out.println("Master password file no longer exists, assuming this is deliberate");
					} else {
						System.err.println("Cannot change password as cannot write new passwords file: "+e);
						e.printStackTrace();
						throw new IOException("cantWriteNewMasterKeysFile", e);
					}
				} catch (MasterKeysWrongPasswordException e) {
					return promptPassword(newThreatLevel, PASSWORD_PROMPT.WRONG, false);
				} catch (MasterKeysFileSizeException e) {
					return errorCorrupt;
				} catch (Node.AlreadySetPasswordException e) {
					System.err.println("Already set a password when changing it - maybe master.keys copied in at the wrong moment???");
				}
			} else if(core.node.getMasterPasswordFile().exists()) {
				//Prompt for the old password, which is needed to decrypt
				return promptPassword(newThreatLevel, PASSWORD_PROMPT.DECRYPT, !passwordIsBlank);
			}

		}
		//Maximum threat level: remove master keys file.
		if (newThreatLevel == SecurityLevels.PHYSICAL_THREAT_LEVEL.MAXIMUM) {
			try {
				core.node.killMasterKeysFile();
			} catch (IOException e) {
				return FirstTimeWizardToadlet.TOADLET_URL+"?step=SECURITY_PHYSICAL&error=delete&newThreatLevel="+newThreatLevel.name();
			}
		}
		setThreatLevel(newThreatLevel, oldThreatLevel);
		//TODO: Mode this to before name selection GET. Steps shouldn't have to have this kind of logic.
		//If opennet is enabled, skip asking for node name as it's not needed. This is to keep things simpler for the user.
		if (core.node.isOpennetEnabled()) {
			return FirstTimeWizardToadlet.TOADLET_URL+"?step="+FirstTimeWizardToadlet.WIZARD_STEP.BANDWIDTH;
		} else {
			return FirstTimeWizardToadlet.TOADLET_URL+"?step="+FirstTimeWizardToadlet.WIZARD_STEP.NAME_SELECTION;
		}
	}

	/**
	 * Internal utility function for displaying a password prompt.
	 * @param newThreatLevel the user-selected threat level, to be used in creating the form.
	 * @param type what type of prompt needed
	 * @param displayZeroLength whether to display the "passwordNotZeroLength" key
	 * @return URL to display the requested page
	 */
	private String promptPassword(SecurityLevels.PHYSICAL_THREAT_LEVEL newThreatLevel, PASSWORD_PROMPT type, boolean displayZeroLength) {
		if (type == PASSWORD_PROMPT.WRONG) {
			System.err.println("Wrong password!");
		}
		StringBuilder destination = new StringBuilder(FirstTimeWizardToadlet.TOADLET_URL+"?step=SECURITY_PHYSICAL&error=pass&newThreatLevel=");
		destination.append(newThreatLevel.name()).append("&type=").append(type.name());
		if (displayZeroLength) {
			destination.append("&zeroLength=true");
		}
		return destination.toString();
	}

	public void setThreatLevel(SecurityLevels.PHYSICAL_THREAT_LEVEL newThreatLevel, SecurityLevels.PHYSICAL_THREAT_LEVEL oldThreatLevel) throws IOException {
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
	}

	//TODO: Able to persist preset
	private void addBackToPhysicalSeclevelsLink(HTMLNode content) {
		content.addChild("a", "href",
		        FirstTimeWizardToadlet.TOADLET_URL+"?step="+FirstTimeWizardToadlet.WIZARD_STEP.SECURITY_PHYSICAL,
		        WizardL10n.l10n("backToSecurityLevels"));
	}
}
