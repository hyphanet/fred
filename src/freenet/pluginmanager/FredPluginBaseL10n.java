/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import freenet.l10n.BaseL10n.LANGUAGE;

/**
 * Interface that has to be implemented for plugins that wants to use
 * the node's localization system (recommended).
 *
 * Those methods are called by the node when plugin l10n data are needed,
 * ex. to automate things in the translation page.
 *
 * @author Artefact2
 */
public interface FredPluginBaseL10n {

	/**
	 * Called when the plugin should change its language.
	 * @param newLanguage New language to use.
	 */
	public void setLanguage(LANGUAGE newLanguage);

	public String getL10nFilesBasePath();

	public String getL10nFilesMask();

	public String getL10nOverrideFilesMask();

	public ClassLoader getPluginClassLoader();
}
