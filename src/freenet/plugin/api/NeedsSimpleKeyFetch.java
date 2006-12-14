/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.plugin.api;

/**
 * Indicates that the plugin requires the ability to fetch keys.
 * Simple version of the interface. Once the plugin is loaded, the
 * node will call register() and thus provide the means to fetch keys.
 * So the plugin author will not forget to implement this interface! :)
 */
public interface NeedsSimpleKeyFetch {

	public void register(ProvidesSimpleKeyFetch fetcher);
	
}
