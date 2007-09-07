/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

/** A plugin which must be notified when fred's list of public ports changes.
 * @author amphibian */
public interface FredPluginNotifyPublicPorts {

	/** Tell the plugin that our public ports list has changed. Called on startup and any time our ports
	 * list changes. */
	public void onChangePublicPorts(int[] ports);
	
}
