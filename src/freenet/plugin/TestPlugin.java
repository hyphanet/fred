/*
  TestPlugin.java / Freenet
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
 * Test plugin. Does absolutely nothing.
 * 
 * @author David 'Bombe' Roden &lt;bombe@freenetproject.org&gt;
 * @version $Id$
 */
public class TestPlugin implements Plugin {

	/**
	 * @see freenet.plugin.Plugin#getPluginName()
	 */
	public String getPluginName() {
		return "Simple Test Plugin";
	}

	/**
	 * @see freenet.plugin.Plugin#setPluginManager(freenet.plugin.PluginManager)
	 */
	public void setPluginManager(PluginManager pluginManager) {
	}

	/**
	 * @see freenet.plugin.Plugin#startPlugin()
	 */
	public void startPlugin() {
	}

	/**
	 * @see freenet.plugin.Plugin#stopPlugin()
	 */
	public void stopPlugin() {
	}

}
