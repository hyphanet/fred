/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.l10n;

import freenet.l10n.BaseL10n.LANGUAGE;
import freenet.pluginmanager.FredPluginBaseL10n;

/**
 * That class basically do the same thing as NodeL10n, except that it's for
 * plugins. Each plugin has to implement FredPluginBaseL10n because each plugin
 * can store its resource files in different locations. The important thing here
 * is that the node can easily access this data to automate translation.
 *
 * Why is this class NOT static ? Because each plugin has is own instance
 * of PluginL10n.
 * @author Artefact2
 */
public class PluginL10n {

	private BaseL10n b;

	/**
	 * Create a new PluginL10n object using the node's selected
	 * language.
	 * @param plugin Plugin to use.
	 */
	public PluginL10n(FredPluginBaseL10n plugin) {
		this(plugin, NodeL10n.getBase().getSelectedLanguage());
	}

	/**
	 * Create a new PluginL10n object.
	 *
	 * Note : you should call this once in your main plugin class, then
	 * store it somewhere static.
	 * @param plugin Plugin to use.
	 * @param lang Language to use.
	 */
	public PluginL10n(FredPluginBaseL10n plugin, final LANGUAGE lang) {
		this.b = new BaseL10n(plugin.getL10nFilesBasePath(),
				plugin.getL10nFilesMask(), plugin.getL10nOverrideFilesMask()
				, lang, plugin.getPluginClassLoader());
	}

	/**
	 * Get the BaseL10n object used by this Plugin.
	 * @return BaseL10n
	 */
	public BaseL10n getBase() {
		return this.b;
	}
}
