/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * Interface that has to be implemented for plugins that want to add fcp commands
 * 
 * 
 * see plugins.FCPHello for a simple sample.
 * 
 * @author saces
 *
 */
public interface FredPluginFCP {
	
	void handle(FCPPluginOutputWrapper replysender, SimpleFieldSet params, Bucket data, boolean fullacess);
	
}
