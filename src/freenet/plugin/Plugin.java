/*
  Plugin.java / Freenet
  Copyright (C) 2005-2006 The Free Network project
  Copyright (C) 2006 David 'Bombe' Roden <bombe@freenetproject.org>
  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

package freenet.plugin;

/**
 * Interface for Fred plugins.
 * 
 * @author David 'Bombe' Roden &lt;bombe@freenetproject.org&gt;
 * @version $Id$
 */
public interface Plugin {

	/**
	 * Returns the name of the plugin.
	 * 
	 * @return The name of the plugin
	 */
	public String getPluginName();

	/**
	 * Sets the plugin manager that manages this plugin.
	 * 
	 * @param pluginManager
	 *            The plugin manager
	 */
	public void setPluginManager(PluginManager pluginManager);

	/**
	 * Starts the plugin. If the plugin needs threads they have to be started
	 * here.
	 */
	public void startPlugin();

	/**
	 * Stops the plugin.
	 */
	public void stopPlugin();

}
