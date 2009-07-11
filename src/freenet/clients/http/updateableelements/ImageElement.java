package freenet.clients.http.updateableelements;

import freenet.client.FetchException;
import freenet.clients.http.FProxyFetchInProgress;
import freenet.clients.http.FProxyFetchResult;
import freenet.clients.http.FProxyFetchTracker;
import freenet.clients.http.SimpleToadletServer;
import freenet.clients.http.ToadletContext;
import freenet.keys.FreenetURI;
import freenet.support.Base64;

public class ImageElement extends BaseUpdateableElement {

	/** The tracker that the Fetcher can be acquired */
	private FProxyFetchTracker		tracker;
	/** The URI of the download this progress bar shows */
	private FreenetURI				key;
	/** The maxSize */
	private long					maxSize;
	/** The FetchListener that gets notified when the download progresses */
	private NotifierFetchListener	fetchListener;

	public ImageElement(FProxyFetchTracker tracker, FreenetURI key, long maxSize, ToadletContext ctx) throws FetchException {
		super("img", ctx);
		this.tracker = tracker;
		this.key = key;
		this.maxSize = maxSize;
		init();
		// Creates and registers the FetchListener
		fetchListener = new NotifierFetchListener(((SimpleToadletServer) ctx.getContainer()).pushDataManager, this);
		tracker.makeFetcher(key, maxSize);
		tracker.getFetchInProgress(key, maxSize).addListener(fetchListener);
	}

	@Override
	public void dispose() {
		// Deregisters the FetchListener
		FProxyFetchInProgress progress = tracker.getFetchInProgress(key, maxSize);
		if (progress != null) {
			progress.removeListener(fetchListener);
		}
	}

	@Override
	public String getUpdaterId(String requestId) {
		return getId(key);
	}

	public static String getId(FreenetURI uri) {
		return Base64.encodeStandard(("image[URI:" + uri.toString() + "]").getBytes());
	}

	@Override
	public String getUpdaterType() {
		return UpdaterConstants.PROGRESSBAR_UPDATER;
	}

	@Override
	public void updateState() {
		children.clear();

		FProxyFetchResult fr = tracker.getFetcher(key, maxSize).getResult();
		if (fr == null) {
			addChild("div", "No fetcher found");
		} else {

			int total = fr.requiredBlocks;
			int fetchedPercent = (int) (fr.fetchedBlocks / (double) total * 100);
			addChild("div","Progress:"+fetchedPercent+"%");
		}
	}

}
