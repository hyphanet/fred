package freenet.config;

import java.io.IOException;
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
	
}
