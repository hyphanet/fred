/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import java.io.File;
import java.net.MalformedURLException;

import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.pluginmanager.PluginInfoWrapper;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

/**
 * load a plugin
 * 
 */
public class LoadPlugin extends FCPMessage {

	static final String NAME = "LoadPlugin";

	static final String TYPENAME_FILE = "file";
	static final String TYPENAME_FREENET = "freenet";
	static final String TYPENAME_OFFICIAL = "official";
	static final String TYPENAME_URL = "url";

	private final String identifier;
	private final String pluginURL;
	private final String urlType;
	private final boolean store;
	private final boolean force;
	private final boolean forceHTTPS;

	public LoadPlugin(SimpleFieldSet fs) throws MessageInvalidException {
		identifier = fs.get("Identifier");
		if(identifier == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Must contain an Identifier field", null, false);
		pluginURL = fs.get("PluginURL");
		if(pluginURL == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Must contain a PluginURL field", identifier, false);
		String type = fs.get("URLType");
		if ((type != null) && (type.trim().length() > 0))
			urlType = type.trim();
		else
			urlType = null;
		if (urlType != null) {
			if (!(TYPENAME_FILE.equalsIgnoreCase(urlType) ||
					TYPENAME_FREENET.equalsIgnoreCase(urlType) ||
					TYPENAME_OFFICIAL.equalsIgnoreCase(urlType) ||
					TYPENAME_URL.equalsIgnoreCase(urlType)))
				throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Unknown URL type: '"+urlType+"'", identifier, false);
		}
		String officialSource = fs.get("OfficialSource");
		if(officialSource != null) {
			if(officialSource.equalsIgnoreCase("https")) {
				force = true;
				forceHTTPS = true;
			} else if(officialSource.equalsIgnoreCase("freenet")) {
				force = true;
				forceHTTPS = false;
			} else {
				throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Unknown OfficialSource '"+officialSource+"'", identifier, false);
			}
		} else {
			force = false;
			forceHTTPS = false;
		}
		store = fs.getBoolean("Store", false);
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet(true);
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public void run(final FCPConnectionHandler handler, final Node node) throws MessageInvalidException {
		if(!handler.hasFullAccess()) {
			throw new MessageInvalidException(ProtocolErrorMessage.ACCESS_DENIED, "LoadPlugin requires full access", identifier, false);
		}
		
		if(!node.pluginManager.isEnabled()) {
			handler.send(new ProtocolErrorMessage(ProtocolErrorMessage.PLUGINS_DISABLED, false, "Plugins disabled", identifier, false));
			return;
		}

		node.executor.execute(new Runnable() {
			@Override
			public void run() {
				String type = null;
				if (urlType == null) {
					if (node.pluginManager.isOfficialPlugin(pluginURL) != null) {
						type = TYPENAME_OFFICIAL;
					} else if (new File(pluginURL).exists()) {
						type = TYPENAME_FILE;
					} else {
						try {
							new FreenetURI(pluginURL);
							type = TYPENAME_FREENET;
						} catch (MalformedURLException e) {
							// FIXME currently i have no idea how to auto detect a proper url,
							// especially distinguish it from typos/mistakes. 
							// so it is disabled for now. saces.
//							try {
//								URL url = new URL(pluginURL);
//								url.getProtocol();
//								TODO: sanitize checks for proper protocols
//							} catch (MalformedURLException e1) {
//							}
						}
					}
					if (type == null) {
						handler.send(new ProtocolErrorMessage(ProtocolErrorMessage.INVALID_FIELD, false, "Was not able to guess the URL type from URL, check the URL or add a 'URLType' field", identifier, false));
						return;
					}
				} else {
					type = urlType.toLowerCase();
				}
				PluginInfoWrapper pi;
				if (TYPENAME_OFFICIAL.equals(type)) {
					pi = node.pluginManager.startPluginOfficial(pluginURL, store, force, forceHTTPS);
				} else if (TYPENAME_FILE.equals(type)) {
					pi = node.pluginManager.startPluginFile(pluginURL, store);
				} else if (TYPENAME_FREENET.equals(type)) {
					pi = node.pluginManager.startPluginFreenet(pluginURL, store);
				} else if (TYPENAME_URL.equals(type)) {
					pi = node.pluginManager.startPluginURL(pluginURL, store);
				} else {
					Logger.error(this, "This should really not happen!", new Exception("FIXME"));
					handler.send(new ProtocolErrorMessage(ProtocolErrorMessage.INTERNAL_ERROR, false, "This should really not happen! See logs for details.", identifier, false));
					return;
				}
				if (pi == null) {
					handler.send(new ProtocolErrorMessage(ProtocolErrorMessage.NO_SUCH_PLUGIN, false, "Plugin '"+ pluginURL + "' does not exist or is not a FCP plugin", identifier, false));
				} else {
					handler.send(new PluginInfoMessage(pi, identifier, true));
				}
			}
		}, "Load Plugin");
	}

}
