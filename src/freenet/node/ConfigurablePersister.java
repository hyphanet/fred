package freenet.node;

import java.io.File;
import java.io.IOException;

import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.l10n.L10n;
import freenet.node.Node.NodeInitException;
import freenet.support.api.StringCallback;

public class ConfigurablePersister extends Persister {

	public ConfigurablePersister(Persistable t, SubConfig nodeConfig, String optionName, 
			String defaultFilename, int sortOrder, boolean expert, boolean forceWrite, String shortDesc, String longDesc, PacketSender ps) throws NodeInitException {
		super(t, ps);
		nodeConfig.register(optionName, defaultFilename, sortOrder, expert, forceWrite, shortDesc, longDesc, new StringCallback() {

			public String get() {
				return persistTarget.toString();
			}

			public void set(String val) throws InvalidConfigValueException {
				setThrottles(val);
			}
			
		});
		
		String throttleFile = nodeConfig.getString(optionName);
		try {
			setThrottles(throttleFile);
		} catch (InvalidConfigValueException e2) {
			throw new NodeInitException(Node.EXIT_THROTTLE_FILE_ERROR, e2.getMessage());
		}
	}

	private void setThrottles(String val) throws InvalidConfigValueException {
		File f = new File(val);
		File tmp = new File(val+".tmp");
		while(true) {
			if(f.exists()) {
				if(!(f.canRead() && f.canWrite()))
					throw new InvalidConfigValueException(l10n("existsCannotReadWrite"));
				break;
			} else {
				try {
					f.createNewFile();
				} catch (IOException e) {
					throw new InvalidConfigValueException(l10n("doesNotExistCannotCreate"));
				}
			}
		}
		while(true) {
			if(tmp.exists()) {
				if(!(tmp.canRead() && tmp.canWrite()))
					throw new InvalidConfigValueException(l10n("existsCannotReadWrite"));
				break;
			} else {
				try {
					tmp.createNewFile();
				} catch (IOException e) {
					throw new InvalidConfigValueException(l10n("doesNotExistCannotCreate"));
				}
			}
		}
		
		synchronized(this) {
			persistTarget = f;
			persistTemp = tmp;
		}
	}

	private String l10n(String key) {
		return L10n.getString("ConfigurablePersister."+key);
	}

}
