/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.ArrayList;
import java.util.Arrays;

import freenet.config.EnumerableOptionCallback;
import freenet.config.InvalidConfigValueException;
import freenet.config.NodeNeedRestartException;
import freenet.config.PersistentConfig;
import freenet.config.SubConfig;
import freenet.support.Logger;
import freenet.support.api.StringCallback;

/**
 * We have 3 basic security settings. The user chooses these in the first-time 
 * wizard, and can reconfigure them at any time. Each impacts on many other
 * config settings, changing their defaults and changing their values when the
 * security level changes, but the user can change those options independantly if
 * they do not change the security level. These options are important, and there
 * are explanations of every option for each setting. They have their own 
 * sub-page on the config toadlet. And the security levels are displayed on the
 * homepage as a useralert (instead of the opennet warning).
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public class SecurityLevels {
	
	public enum NETWORK_THREAT_LEVEL {
		HIGH, // paranoid, darknet only
		NORMAL, // normal setting, darknet/opennet hybrid
		LOW // turn off every performance impacting security measure
	}
	
	public enum FRIENDS_THREAT_LEVEL {
		HIGH, // Share no/minimal information and take measures to reduce harm if Friends are compromized
		NORMAL, // Share some information
		LOW // Friends are ultimately trusted
	}
	
	public enum PHYSICAL_THREAT_LEVEL {
		NORMAL, // Encrypt temp files etc etc
		LOW // Don't encrypt temp files etc etc
	}
	
	NETWORK_THREAT_LEVEL networkThreatLevel;
	FRIENDS_THREAT_LEVEL friendsThreatLevel;
	PHYSICAL_THREAT_LEVEL physicalThreatLevel;
	
	private MyCallback<NETWORK_THREAT_LEVEL> networkThreatLevelCallback;
	private MyCallback<FRIENDS_THREAT_LEVEL> friendsThreatLevelCallback;
	private MyCallback<PHYSICAL_THREAT_LEVEL> physicalThreatLevelCallback;
	
	public SecurityLevels(Node node, PersistentConfig config) {
		SubConfig myConfig = new SubConfig("security-levels", config);
		int sortOrder = 0;
		networkThreatLevelCallback = new MyCallback<NETWORK_THREAT_LEVEL>() {

			@Override
			public String get() {
				return networkThreatLevel.name();
			}

			public String[] getPossibleValues() {
				NETWORK_THREAT_LEVEL[] values = NETWORK_THREAT_LEVEL.values();
				String[] names = new String[values.length];
				for(int i=0;i<names.length;i++)
					names[i] = values[i].name();
				return names;
			}

			@Override
			protected NETWORK_THREAT_LEVEL getValue() {
				return networkThreatLevel;
			}

			@Override
			protected void setValue(String val) throws InvalidConfigValueException {
				NETWORK_THREAT_LEVEL newValue = NETWORK_THREAT_LEVEL.valueOf(val);
				if(newValue != null)
					throw new InvalidConfigValueException("Invalid value for network threat level: "+val);
			}

		};
		myConfig.register("networkThreatLevel", "NORMAL", sortOrder++, false, true, "SecurityLevels.networkThreatLevelShort", "SecurityLevels.networkThreatLevel", networkThreatLevelCallback);
		networkThreatLevel = NETWORK_THREAT_LEVEL.valueOf(myConfig.getString("networkThreatLevel"));
		friendsThreatLevelCallback = new MyCallback<FRIENDS_THREAT_LEVEL>() {

			@Override
			public String get() {
				return friendsThreatLevel.name();
			}

			public String[] getPossibleValues() {
				FRIENDS_THREAT_LEVEL[] values = FRIENDS_THREAT_LEVEL.values();
				String[] names = new String[values.length];
				for(int i=0;i<names.length;i++)
					names[i] = values[i].name();
				return names;
			}

			@Override
			protected FRIENDS_THREAT_LEVEL getValue() {
				return friendsThreatLevel;
			}

			@Override
			protected void setValue(String val) throws InvalidConfigValueException {
				FRIENDS_THREAT_LEVEL newValue = FRIENDS_THREAT_LEVEL.valueOf(val);
				if(newValue != null)
					throw new InvalidConfigValueException("Invalid value for friends threat level: "+val);
			}

		};
		myConfig.register("friendsThreatLevel", "NORMAL", sortOrder++, false, true, "SecurityLevels.friendsThreatLevelShort", "SecurityLevels.friendsThreatLevel", friendsThreatLevelCallback);
		friendsThreatLevel = FRIENDS_THREAT_LEVEL.valueOf(myConfig.getString("friendsThreatLevel"));
		physicalThreatLevelCallback = new MyCallback<PHYSICAL_THREAT_LEVEL>() {

			@Override
			public String get() {
				return physicalThreatLevel.name();
			}

			public String[] getPossibleValues() {
				PHYSICAL_THREAT_LEVEL[] values = PHYSICAL_THREAT_LEVEL.values();
				String[] names = new String[values.length];
				for(int i=0;i<names.length;i++)
					names[i] = values[i].name();
				return names;
			}

			@Override
			protected PHYSICAL_THREAT_LEVEL getValue() {
				return physicalThreatLevel;
			}

			@Override
			protected void setValue(String val) throws InvalidConfigValueException {
				PHYSICAL_THREAT_LEVEL newValue = PHYSICAL_THREAT_LEVEL.valueOf(val);
				if(newValue != null)
					throw new InvalidConfigValueException("Invalid value for physical threat level: "+val);
			}

		};
		myConfig.register("physicalThreatLevel", "NORMAL", sortOrder++, false, true, "SecurityLevels.physicalThreatLevelShort", "SecurityLevels.physicalThreatLevel", physicalThreatLevelCallback);
		physicalThreatLevel = PHYSICAL_THREAT_LEVEL.valueOf(myConfig.getString("physicalThreatLevel"));
	}
	
	public void addNetworkThreatLevelListener(SecurityLevelListener<NETWORK_THREAT_LEVEL> listener) {
		networkThreatLevelCallback.addListener(listener);
	}
	
	public void addFriendsThreatLevelListener(SecurityLevelListener<FRIENDS_THREAT_LEVEL> listener) {
		friendsThreatLevelCallback.addListener(listener);
	}
	
	public void addPhysicalThreatLevelListener(SecurityLevelListener<PHYSICAL_THREAT_LEVEL> listener) {
		physicalThreatLevelCallback.addListener(listener);
	}
	
	private abstract class MyCallback<T> extends StringCallback implements EnumerableOptionCallback {

		private ArrayList<SecurityLevelListener<T>> listeners;
		
		public void addListener(SecurityLevelListener<T> listener) {
			if(listeners.contains(listener)) {
				Logger.error(this, "Already have listener "+listener+" in "+this);
				return;
			}
			listeners.add(listener);
		}
		
		public void setPossibleValues(String[] val) {
			throw new UnsupportedOperationException();
		}
		
		@Override
		public void set(String val) throws InvalidConfigValueException, NodeNeedRestartException {
			T oldLevel = getValue();
			setValue(val);
			T newLevel = getValue();
			for(SecurityLevelListener<T> listener : listeners) {
				listener.onChange(oldLevel, newLevel);
			}
		}

		protected abstract void setValue(String val) throws InvalidConfigValueException;

		protected abstract T getValue();
		
	}

	public NETWORK_THREAT_LEVEL getNetworkThreatLevel() {
		return networkThreatLevel;
	}
	
}
