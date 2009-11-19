package freenet.node.updater;

import freenet.client.FetchResult;
import freenet.keys.FreenetURI;

public class ExtJarUpdater extends NodeUpdater {

	ExtJarUpdater(NodeUpdateManager manager, FreenetURI URI, int current, int min, int max, String blobFilenamePrefix) {
		super(manager, URI, current, min, max, blobFilenamePrefix);
	}

	@Override
	public String jarName() {
		return "freenet-ext.jar";
	}
	
	protected void processSuccess() {
		manager.onDownloadedNewJar(true, -1, -1);
	}

	@Override
	protected void onStartFetching() {
		manager.onStartFetching(true);
	}

	@Override
	protected void maybeParseManifest(FetchResult result) {
		// Do nothing.
	}


	
}
