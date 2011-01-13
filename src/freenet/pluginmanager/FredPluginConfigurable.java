/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import freenet.config.SubConfig;

/**
 * Implement this if your plugin needs to store configuration
 * parameters. The node will handle loading and saving the parameters.
 * It will also provide a user interface to modify them. An entry will
 * be made in the global Configuration menu dedicated to the plugin's
 * configuration. The plugin must implement the @link FredPluginL10n
 * interface, to allow translation of the config parameter
 * descriptions.
 *
 * The l10n key for the menu label is
 * "ConfigToadlet.full.package.Classname.label". The key for the menu
 * tooltip is "ConfigToadlet.full.package.Classname.tooltip".
 *
 * The parameters are stored in an unencrypted plaintext file in the
 * node's configuration directory using the filename
 * plugin-full.package.Classname.ini.
 *
 * Plugins may force a write of the configuration file by calling
 * pluginRespirator.storeConfig(), but this is only necessary if the
 * plugin modifes a parameter behind the user's back.
 */
public interface FredPluginConfigurable extends FredPluginL10n {
	/**
	 * Setup the plugin's configuration options. This method is called
	 * before the plugin's runPlugin method, but not in a new
	 * thread. Plugins should register options on the supplied
	 * SubConfig, but they should not do any other initialization.
	 */
	public void setupConfig(SubConfig subconfig);
}
