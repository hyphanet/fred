package freenet.node;

import java.io.File;
import java.io.IOException;

import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.node.Node.NodeInitException;
import freenet.support.api.StringCallback;

public class ConfigurablePersister extends Persister {

	public ConfigurablePersister(Persistable t, SubConfig nodeConfig, String optionName, 
			String defaultFilename, int sortOrder, boolean expert, boolean forceWrite, String shortDesc, String longDesc) throws NodeInitException {
		super(t);
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
					throw new InvalidConfigValueException("File exists and cannot read/write it");
				break;
			} else {
				try {
					f.createNewFile();
				} catch (IOException e) {
					throw new InvalidConfigValueException("File does not exist and cannot be created");
				}
			}
		}
		while(true) {
			if(tmp.exists()) {
				if(!(tmp.canRead() && tmp.canWrite()))
					throw new InvalidConfigValueException("File exists and cannot read/write it");
				break;
			} else {
				try {
					tmp.createNewFile();
				} catch (IOException e) {
					throw new InvalidConfigValueException("File does not exist and cannot be created");
				}
			}
		}
		
		Persister tp;
		synchronized(this) {
			persistTarget = f;
			persistTemp = tmp;
		}
	}

}
