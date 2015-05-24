/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * Interface that has to be implemented for plugins that wants talk to
 * other plugins that implements FredPluginFCP
 *  
 * @author saces
 * @deprecated Use {@link FredPluginFCPMessageHandler.ClientSideFCPMessageHandler} instead.
 */
@Deprecated
public interface FredPluginTalker {
	
	/**
	 * @param pluginname - reply from
	 * @param indentifier - identifer from your call
	 * @param params parameters passed back
	 * @param data a bucket of data passed back, can be null
	 */
	void onReply(String pluginname, String indentifier, SimpleFieldSet params, Bucket data);
	
}
