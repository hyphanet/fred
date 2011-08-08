package freenet.clients.http.wizardsteps;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.FirstTimeWizardToadlet;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.config.Config;
import freenet.config.ConfigException;
import freenet.l10n.NodeL10n;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;

import java.io.IOException;

/**
 * Allows the user to choose whether to enable auto-updating, and what official utility plugins to install.
 */
public class MISC extends Toadlet implements Step {

	private final Config config;
	private final NodeClientCore core;

	public MISC(NodeClientCore core, Config config, HighLevelSimpleClient client) {
		super(client);
		this.core = core;
		this.config = config;
	}

	@Override
	public String path() {
		return FirstTimeWizardToadlet.TOADLET_URL+"?step=MISC";
	}

	@Override
	public String getTitleKey() {
		return "stepMiscTitle";
	}

	@Override
	public void getStep(HTMLNode contentNode, HTTPRequest request, ToadletContext ctx) {
		HTMLNode form = ctx.addFormChild(contentNode, ".", "miscForm");

		HTMLNode miscInfobox = form.addChild("div", "class", "infobox infobox-normal");
		HTMLNode miscInfoboxHeader = miscInfobox.addChild("div", "class", "infobox-header");
		HTMLNode miscInfoboxContent = miscInfobox.addChild("div", "class", "infobox-content");

		miscInfoboxHeader.addChild("#", WizardL10n.l10n("autoUpdate"));
		miscInfoboxContent.addChild("p", WizardL10n.l10n("autoUpdateLong"));
		miscInfoboxContent.addChild("p").addChild("input",
		        new String[] { "type", "checked", "name", "value" },
		        new String[] { "radio", "on", "autodeploy", "true" }, WizardL10n.l10n("autoUpdateAutodeploy"));
		miscInfoboxContent.addChild("p").addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "radio", "autodeploy", "false" }, WizardL10n.l10n("autoUpdateNoAutodeploy"));

		miscInfobox = form.addChild("div", "class", "infobox infobox-normal");
		miscInfoboxHeader = miscInfobox.addChild("div", "class", "infobox-header");
		miscInfoboxContent = miscInfobox.addChild("div", "class", "infobox-content");

		miscInfoboxHeader.addChild("#", WizardL10n.l10n("plugins"));
		miscInfoboxContent.addChild("p", WizardL10n.l10n("pluginsLong"));
		miscInfoboxContent.addChild("p").addChild("input",
		        new String[] { "type", "checked", "name", "value" },
		        new String[] { "checkbox", "on", "upnp", "true" }, WizardL10n.l10n("enableUPnP"));

		//Marker for step on POST side
		miscInfoboxContent.addChild("input",
		        new String [] { "type", "name", "value" },
		        new String [] { "hidden", "step", "MISC" });
		miscInfoboxContent.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "miscF", WizardL10n.l10n("continue")});
		miscInfoboxContent.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "cancel", NodeL10n.getBase().getString("Toadlet.cancel")});
	}

	@Override
	public void postStep(HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
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
		super.writeTemporaryRedirect(ctx, "step7", FirstTimeWizardToadlet.TOADLET_URL+"?step="+FirstTimeWizardToadlet.WIZARD_STEP.OPENNET);
	}
}
