package freenet.clients.http.wizardsteps;

import freenet.clients.http.FirstTimeWizardToadlet;
import freenet.config.Config;
import freenet.config.ConfigException;
import freenet.l10n.NodeL10n;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;

/**
 * Allows the user to choose whether to enable auto-updating, and what official utility plugins to install.
 */
public class MISC implements Step {

	private final Config config;
	private final NodeClientCore core;

	public MISC(NodeClientCore core, Config config) {
		this.core = core;
		this.config = config;
	}

	@Override
	public void getStep(HTTPRequest request, PageHelper helper) {
		HTMLNode contentNode = helper.getPageContent(WizardL10n.l10n("stepMiscTitle"));
		HTMLNode form = helper.addFormChild(contentNode, ".", "miscForm");

		HTMLNode miscInfoboxContent = helper.getInfobox("infobox-normal", WizardL10n.l10n("autoUpdate"),
		        form, null, false);

		miscInfoboxContent.addChild("p", WizardL10n.l10n("autoUpdateLong"));
		miscInfoboxContent.addChild("p").addChild("input",
		        new String[] { "type", "checked", "name", "value" },
		        new String[] { "radio", "on", "autodeploy", "true" }, WizardL10n.l10n("autoUpdateAutodeploy"));
		miscInfoboxContent.addChild("p").addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "radio", "autodeploy", "false" }, WizardL10n.l10n("autoUpdateNoAutodeploy"));

		miscInfoboxContent = helper.getInfobox("infobox-normal", WizardL10n.l10n("plugins"),
		        form, null, false);

		miscInfoboxContent.addChild("p", WizardL10n.l10n("pluginsLong"));
		miscInfoboxContent.addChild("p").addChild("input",
		        new String[] { "type", "checked", "name", "value" },
		        new String[] { "checkbox", "on", "upnp", "true" }, WizardL10n.l10n("enableUPnP"));
		miscInfoboxContent.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "back", NodeL10n.getBase().getString("Toadlet.back")});
		miscInfoboxContent.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "next", NodeL10n.getBase().getString("Toadlet.next")});
	}

	@Override
	public String postStep(HTTPRequest request) {
		setAutoUpdate(Boolean.parseBoolean(request.getPartAsStringFailsafe("autodeploy", 10)));
		setUPnP(request.isPartSet("upnp"));
		return FirstTimeWizardToadlet.WIZARD_STEP.OPENNET.name();
	}

	/**
	 * Sets whether auto-update should be enabled.
	 * @param enabled whether auto-update should be enabled.
	 */
	public void setAutoUpdate(boolean enabled) {
		try {
			config.get("node.updater").set("autoupdate", enabled);
		} catch (ConfigException e) {
			Logger.error(this, "Should not happen, please report!" + e, e);
		}
	}

	/**
	 * Enables or disables the UPnP plugin asynchronously. If the plugin's state would not change for the given
	 * argument, it does nothing.
	 * @param enableUPnP whether UPnP should be enabled.
	 */
	public void setUPnP(final boolean enableUPnP) {
		//If its state would not change, don't do anything.
		if(enableUPnP == core.node.pluginManager.isPluginLoaded("plugins.UPnP.UPnP")) {
				return;
		}

		core.node.executor.execute(new Runnable() {

			private final boolean enable = enableUPnP;

			@Override
			public void run() {
				if(enable) {
					core.node.pluginManager.startPluginOfficial("UPnP", true, false, false);
				} else {
					core.node.pluginManager.killPluginByClass("plugins.UPnP.UPnP", 5000);
				}
			}

		});
	}
}
