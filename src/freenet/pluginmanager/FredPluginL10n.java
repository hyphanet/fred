/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import freenet.l10n.BaseL10n.LANGUAGE;

/**
 * Interface that has to be implemented for plugins that wants to use
 * PageMaker.addNavigationLink(..) 
 * 
 * @author saces
 */
public interface FredPluginL10n {

	public String getString(String key);
	
	public void setLanguage(LANGUAGE newLanguage);
}
