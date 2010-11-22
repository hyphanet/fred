package freenet.node;

import java.io.File;
import java.io.IOException;

import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.l10n.NodeL10n;
import freenet.support.Ticker;
import freenet.support.api.StringCallback;

public class ConfigurablePersister extends Persister {

	public ConfigurablePersister(Persistable t, SubConfig nodeConfig, String optionName, 
			String defaultFilename, int sortOrder, boolean expert, boolean forceWrite, String shortDesc, String longDesc, Ticker ps, File baseDir) throws NodeInitException {
		super(t, ps);
		nodeConfig.register(optionName, new File(baseDir, defaultFilename).toString(), sortOrder, expert, forceWrite, shortDesc, longDesc, new StringCallback() {

			@Override
			public String get() {
				return persistTarget.toString();
			}

			@Override
			public void set(String val) throws InvalidConfigValueException {
				setThrottles(val);
			}
		});
		
		String throttleFile = nodeConfig.getString(optionName);
		try {
			setThrottles(throttleFile);
		} catch (InvalidConfigValueException e2) {
			throw new NodeInitException(NodeInitException.EXIT_THROTTLE_FILE_ERROR, e2.getMessage());
		}
	}

	private void setThrottles(String val) throws InvalidConfigValueException {
		File f = new File(val);
		File tmp = new File(f.toString()+".tmp");
		while(true) {
			if(f.exists()) {
				if(!(f.canRead() && f.canWrite()))
					throw new InvalidConfigValueException(l10n("existsCannotReadWrite")+" : "+tmp);
				break;
			} else {
				try {
					if(!f.createNewFile()) {
						if(f.exists()) continue;
						throw new InvalidConfigValueException(l10n("doesNotExistCannotCreate")+" : "+tmp);
					}
				} catch (IOException e) {
					throw new InvalidConfigValueException(l10n("doesNotExistCannotCreate")+" : "+tmp);
				}
			}
		}
		while(true) {
			if(tmp.exists()) {
				if(!(tmp.canRead() && tmp.canWrite()))
					throw new InvalidConfigValueException(l10n("existsCannotReadWrite")+" : "+tmp);
				break;
			} else {
				try {
					tmp.createNewFile();
				} catch (IOException e) {
					throw new InvalidConfigValueException(l10n("doesNotExistCannotCreate")+" : "+tmp);
				}
			}
		}
		
		synchronized(this) {
			persistTarget = f;
			persistTemp = tmp;
		}
	}

	private String l10n(String key) {
		return NodeL10n.getBase().getString("ConfigurablePersister."+key);
	}

}
