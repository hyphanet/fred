package freenet.node.updater;

import freenet.keys.FreenetURI;
import freenet.support.Logger;

public class MainJarUpdater extends NodeUpdater {

	MainJarUpdater(NodeUpdateManager manager, FreenetURI URI, int current, int min, int max, String blobFilenamePrefix) {
		super(manager, URI, current, min, max, blobFilenamePrefix);
	}

	private int requiredExt;
	private int recommendedExt;

	private static final String REQUIRED_EXT_PREFIX = "Required-Ext-Version: ";
	private static final String RECOMMENDED_EXT_PREFIX = "Recommended-Ext-Version: ";
	
	@Override
	public String jarName() {
		return "freenet.jar";
	}

	protected void parseManifestLine(String line) {
		if(line.startsWith(REQUIRED_EXT_PREFIX)) {
			requiredExt = Integer.parseInt(line.substring(REQUIRED_EXT_PREFIX.length()));
		} else if(line.startsWith(RECOMMENDED_EXT_PREFIX)) {
			recommendedExt = Integer.parseInt(line.substring(RECOMMENDED_EXT_PREFIX.length()));
		}
	}
	
	/** Called with locks held */
	protected void maybeParseManifest() {
		requiredExt = -1;
		recommendedExt = -1;
		parseManifest();
		if(requiredExt != -1) {
			System.err.println("Required ext version: "+requiredExt);
			Logger.normal(this, "Required ext version: "+requiredExt);
		}
		if(recommendedExt != -1) {
			System.err.println("Recommended ext version: "+recommendedExt);
			Logger.normal(this, "Recommended ext version: "+recommendedExt);
		}
	}

	protected void processSuccess() {
		manager.onDownloadedNewJar(false, requiredExt, recommendedExt);
	}

	@Override
	protected void onStartFetching() {
		manager.onStartFetching(false);
	}

	
}
