package freenet.clients.http.wizardsteps;

import freenet.clients.http.FirstTimeWizardToadlet;
import freenet.l10n.NodeL10n;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * This step gives the user information about browser usage.
 */
public class BROWSER_WARNING implements Step {

	@Override
	public void getStep(HTTPRequest request, PageHelper helper) {
		boolean incognito = request.isParameterSet("incognito");
		// Bug 3376: Opening Chrome in incognito mode from command line will open a new non-incognito window if the browser is already open.
		// See http://code.google.com/p/chromium/issues/detail?id=9636
		// This is fixed upstream but we need to test for fixed versions of Chrome.
		// Bug 5210: Same for Firefox!
		// Note also that Firefox 4 and later are much less vulnerable to css link:visited attacks,
		// but are not completely immune, especially if the bad guy can guess the site url. Ideally
		// the user should turn off link:visited styling altogether.
		// FIXME detect recent firefox and tell the user how they could improve their privacy further.
		// See:
		// http://blog.mozilla.com/security/2010/03/31/plugging-the-css-history-leak/
		// http://dbaron.org/mozilla/visited-privacy#limits
		// http://jeremiahgrossman.blogspot.com/2006/08/i-know-where-youve-been.html
		// https://developer.mozilla.org/en/Firefox_4_for_developers
		// https://developer.mozilla.org/en/CSS/Privacy_and_the_%3avisited_selector
		String ua = request.getHeader("user-agent");
		boolean isFirefox = false;
		boolean isOldFirefox = false;
		boolean showTabWarning = false;
		if(ua != null) {
			isFirefox = ua.contains("Firefox/");
			//Firefox 3.6 can destroy tabs, see https://bugs.freenetproject.org/view.php?id=5209
			if(ua.contains("Firefox/3.6") && incognito) {
				showTabWarning = true;
			} else if (isFirefox) {
				//Versions of Firefox other than 3.6 do not behave properly when going into
				//privacy mode from the command line, so show the warnings about the lack of
				//being in privacy mode.
				incognito = false;
			}
			if(ua.contains("Firefox/0.") ||
			   ua.contains("Firefox/1.") ||
			   ua.contains("Firefox/2.") ||
			   ua.contains("Firefox/3.")) {
				isOldFirefox = true;
			}
		}
		boolean isRelativelySafe = isFirefox && !isOldFirefox;

		HTMLNode contentNode = helper.getPageContent(WizardL10n.l10n("browserWarningPageTitle"));

		String infoBoxHeader;
		if(incognito) {
			infoBoxHeader = WizardL10n.l10n("browserWarningIncognitoShort");
		} else if (isRelativelySafe) {
			infoBoxHeader = WizardL10n.l10n("browserWarningShortRelativelySafe");
		} else {
			infoBoxHeader = WizardL10n.l10n("browserWarningShort");
		}
		
		HTMLNode infoboxContent = helper.getInfobox("infobox-normal", infoBoxHeader, contentNode, null, false);

		if(isOldFirefox) {
			HTMLNode p = infoboxContent.addChild("p");
			p.addChild("#", WizardL10n.l10n("browserWarningOldFirefox"));
			if (showTabWarning) {
				p.addChild("#", " " + WizardL10n.l10n("browserWarningFirefoxMightHaveClobberedTabs"));
			} else if(!incognito) {
				p.addChild("#", " " + WizardL10n.l10n("browserWarningOldFirefoxNewerHasPrivacyMode"));
			}
		}

		if(isRelativelySafe) {
			infoboxContent.addChild("p", incognito ?
			        WizardL10n.l10n("browserWarningIncognitoMaybeSafe") :
			        WizardL10n.l10n("browserWarningMaybeSafe"));
		} else {
			NodeL10n.getBase().addL10nSubstitution(infoboxContent, incognito ?
			        "FirstTimeWizardToadlet.browserWarningIncognito" :
			        "FirstTimeWizardToadlet.browserWarning",
			        new String[] { "bold" },
			        new HTMLNode[] { HTMLNode.STRONG });
		}

		if(incognito) {
			infoboxContent.addChild("p", WizardL10n.l10n("browserWarningIncognitoSuggestion"));
		} else {
			infoboxContent.addChild("p", WizardL10n.l10n("browserWarningSuggestion"));
		}
		infoboxContent.addChild("p", WizardL10n.l10n("browserImeWarning"));

		HTMLNode form = helper.addFormChild(infoboxContent.addChild("p"), ".", "continueForm");
		form.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "back", NodeL10n.getBase().getString("Toadlet.back")});
		form.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "next", NodeL10n.getBase().getString("Toadlet.next")});
	}

	/**
	 * This POST side just continues to the next step.
	 * @param request Unused.
	 */
	@Override
	public String postStep(HTTPRequest request) {
		return FirstTimeWizardToadlet.WIZARD_STEP.MISC.name();
	}
}
