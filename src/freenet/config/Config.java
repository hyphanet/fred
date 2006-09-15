/*
  Config.java / Freenet
  Copyright (C) 2005-2006 The Free Network project

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

package freenet.config;

import java.util.HashMap;

/** Global configuration object for a node. SubConfig's register here.
 * Handles writing to a file etc.
 */
public class Config {

	protected final HashMap configsByPrefix;
	
	public Config() {
		configsByPrefix = new HashMap();
	}
	
	public void register(SubConfig sc) {
		synchronized(this) {
			if(configsByPrefix.containsKey(sc.prefix))
				throw new IllegalArgumentException("Already registered "+sc.prefix+": "+sc);
			configsByPrefix.put(sc.prefix, sc);
		}
	}
	
	/** Write current config to disk 
	 * @throws IOException */
	public void store() {
		// Do nothing
	}

	/** Finished initialization */
	public void finishedInit() {
		// Do nothing
	}

	public void onRegister(SubConfig config, Option o) {
		// Do nothing
	}

	/** Fetch all the SubConfig's. Used by user-facing config thingies. */
	public synchronized SubConfig[] getConfigs() {
		return (SubConfig[]) configsByPrefix.values().toArray(new SubConfig[configsByPrefix.size()]);
	}
	
	public synchronized SubConfig get(String subConfig){
		return (SubConfig)configsByPrefix.get(subConfig);
	}
	
}
