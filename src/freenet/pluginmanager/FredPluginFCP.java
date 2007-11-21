/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * Interface that has to be implemented for plugins that want to be talkable
 * via fcp or direct (plugin to plugin)
 *  
 * see plugins.FCPHello for a simple sample.
 * 
 * @author saces
 *
 */
public interface FredPluginFCP {
	
	/**
	 * @param replysender interface to send a reply
	 * @param params parameters passed in
	 * @param data a bucket of data passed in, can be null
	 * @param access null - direct call (plugin to plugin), if set: FCP full access 
	 */
	void handle(PluginReplySender replysender, SimpleFieldSet params, Bucket data, Boolean access);
	
}
