/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

/** Implement this if you do not need to do anything in 
 * @link FredPlugin.run(), and your plugin should get run() for 
 * initialisation but will continue until it is explicitly removed, and 
 * will either do all its work in callback methods, or will allocate its
 * own threads or schedule jobs on the ticker etc. */
public interface FredPluginThreadless {
	
}
