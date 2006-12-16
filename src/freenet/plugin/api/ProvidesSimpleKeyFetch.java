/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.plugin.api;

/**
 * Provides an interface to the client for it to fetch keys from.
 * Simple version of the interface.
 */
public interface ProvidesSimpleKeyFetch {

	public PluginFetchResult fetch(PluginFreenetURI uri) throws PluginFetchException;
	
}
