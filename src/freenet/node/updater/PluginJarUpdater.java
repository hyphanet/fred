package freenet.node.updater;

import java.io.IOException;

import freenet.clients.http.PproxyToadlet;
import freenet.keys.FreenetURI;
import freenet.l10n.BaseL10n;
import freenet.l10n.NodeL10n;
import freenet.node.Version;
import freenet.node.useralerts.AbstractUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.pluginmanager.PluginInfoWrapper;
import freenet.pluginmanager.PluginManager;
import freenet.support.HTMLNode;
import freenet.support.Logger;

public class PluginJarUpdater extends NodeUpdater {

	final String pluginName;
	final PluginManager pluginManager;
	private boolean autoDeployOnRestart;
	private UserAlert alert;
	
	PluginJarUpdater(NodeUpdateManager manager, FreenetURI URI, int current, int min, int max, String blobFilenamePrefix, String pluginName, PluginManager pm, boolean autoDeployOnRestart) {
		super(manager, URI, current, min, max, blobFilenamePrefix);
		this.pluginName = pluginName;
		this.pluginManager = pm;
		this.autoDeployOnRestart = autoDeployOnRestart;
	}

	@Override
	public String jarName() {
		return pluginName;
	}

	private int requiredNodeVersion;
	
	private static final String REQUIRED_NODE_VERSION_PREFIX = "Required-Node-Version: ";
	
	@Override
	protected void maybeParseManifest() {
		requiredNodeVersion = -1;
		parseManifest();
		if(requiredNodeVersion != -1) {
			System.err.println("Required node version for plugin "+pluginName+": "+requiredNodeVersion);
			Logger.normal(this, "Required node version for plugin "+pluginName+": "+requiredNodeVersion);
		}
	}
	
	protected void parseManifestLine(String line) {
		if(line.startsWith(REQUIRED_NODE_VERSION_PREFIX)) {
			requiredNodeVersion = Integer.parseInt(line.substring(REQUIRED_NODE_VERSION_PREFIX.length()));
		}
	}
	
	@Override
	protected void onStartFetching() {
		System.err.println("Starting to fetch plugin "+pluginName);
	}

	@Override
	protected void processSuccess() {
		synchronized(this) {
			if(requiredNodeVersion > Version.buildNumber()) {
				System.err.println("Found version "+fetchedVersion+" of "+pluginName+" but needs node version "+requiredNodeVersion);
				// FIXME deploy it with the main jar
				tempBlobFile.delete();
				return;
			}
		}
		
		PluginInfoWrapper loaded = pluginManager.getPluginInfo(pluginName);
		
		if(loaded == null) {
			if(!node.pluginManager.isPluginLoadedOrLoadingOrWantLoad(pluginName)) {
				System.err.println("Don't want plugin: "+pluginName);
				Logger.error(this, "Don't want plugin: "+pluginName);
				tempBlobFile.delete();
				return;
			}
		}
		
		if(loaded.getPluginLongVersion() >= fetchedVersion) {
			tempBlobFile.delete();
			return;
		}
		if(autoDeployOnRestart) {
			try {
				writeJar();
			} catch (IOException e) {
				System.err.println("Unable to write new plugin jar for "+pluginName+": "+e);
				e.printStackTrace();
				Logger.error(this, "Unable to write new plugin jar for "+pluginName+": "+e, e);
				tempBlobFile.delete();
				return;
			}
		}
		
		// Create a useralert to ask the user to deploy the new version.
		
		UserAlert toRegister = null;
		synchronized(this) {
			if(alert != null) return;
			
			HTMLNode div = new HTMLNode("div");
			// Text saying the plugin has been updated...
			div.addChild("#", l10n("pluginUpdatedText", new String[] { "name", "newVersion" }, new String[] { pluginName, Long.toString(fetchedVersion) }));
			if(autoDeployOnRestart)
				div.addChild("#", " " + l10n("willAutoDeployOnRestart"));
			
			// Form to deploy the updated version.
			// This is not the same as reloading because we haven't written it yet.
			
			HTMLNode formNode = div.addChild("form", new String[] { "action", "method" }, new String[] { PproxyToadlet.PATH, "post" });
			formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", node.clientCore.formPassword });
			formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "update", pluginName });
			formNode.addChild("input", new String[] { "type", "value" }, new String[] { "submit", l10n("updatePlugin") });
			
			toRegister = alert = new AbstractUserAlert(true, l10n("pluginUpdatedTitle", "name", pluginName), l10n("pluginUpdatedText", "name", pluginName), l10n("pluginUpdatedShortText", "name", pluginName), div, UserAlert.ERROR, true, NodeL10n.getBase().getString("UserAlert.hide"), true, this) {
				
				public void onDismiss() {
					synchronized(PluginJarUpdater.this) {
						alert = null;
					}
				}
			};
		}
		if(toRegister != null)
			node.clientCore.alerts.register(toRegister);
	}

	private String l10n(String key) {
		return NodeL10n.getBase().getString("PluginJarUpdater."+key);
	}

	private String l10n(String key, String name, String value) {
		return NodeL10n.getBase().getString("PluginJarUpdater."+key, name, value);
	}

	private String l10n(String key, String[] names, String[] values) {
		return NodeL10n.getBase().getString("PluginJarUpdater."+key, names, values);
	}

	void writeJar() throws IOException {
		writeJarTo(pluginManager.getPluginFilename(pluginName));
		UserAlert a;
		synchronized(this) {
			a = alert;
			alert = null;
		}
		if(a != null)
			node.clientCore.alerts.unregister(a);
	}

}
