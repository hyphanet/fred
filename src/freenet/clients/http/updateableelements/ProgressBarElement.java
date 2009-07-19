package freenet.clients.http.updateableelements;

import java.text.NumberFormat;

import freenet.clients.http.FProxyFetchInProgress;
import freenet.clients.http.FProxyFetchResult;
import freenet.clients.http.FProxyFetchTracker;
import freenet.clients.http.FProxyFetchWaiter;
import freenet.clients.http.SimpleToadletServer;
import freenet.clients.http.ToadletContext;
import freenet.keys.FreenetURI;
import freenet.l10n.L10n;
import freenet.support.Base64;

/** A pushed element that renders the progress bar when loading a page. */
public class ProgressBarElement extends BaseUpdateableElement {

	/** The tracker that the Fetcher can be acquired */
	private FProxyFetchTracker		tracker;
	/** The URI of the download this progress bar shows */
	private FreenetURI				key;
	/** The maxSize */
	private long					maxSize;
	/** The FetchListener that gets notified when the download progresses */
	private NotifierFetchListener	fetchListener;

	public ProgressBarElement(FProxyFetchTracker tracker, FreenetURI key, long maxSize, ToadletContext ctx) {
		// This is a <div>
		super("div", "class", "progressbar", ctx);
		this.tracker = tracker;
		this.key = key;
		this.maxSize = maxSize;
		init();
		// Creates and registers the FetchListener
		fetchListener = new NotifierFetchListener(((SimpleToadletServer) ctx.getContainer()).pushDataManager, this);
		tracker.getFetchInProgress(key, maxSize).addListener(fetchListener);
	}

	@Override
	public void updateState() {
		children.clear();

		FProxyFetchWaiter waiter=tracker.getFetcher(key, maxSize);
		FProxyFetchResult fr = waiter.getResult();
		if (fr == null) {
			addChild("div", "No fetcher found");
		}
		if (fr.isFinished() || fr.hasData() || fr.failed != null) {
			// If finished then we just send a FINISHED text. It will reload the page
			setContent(UpdaterConstants.FINISHED);
		} else {
			int total = fr.requiredBlocks;
			int fetchedPercent = (int) (fr.fetchedBlocks / (double) total * 100);
			int failedPercent = (int) (fr.failedBlocks / (double) total * 100);
			int fatallyFailedPercent = (int) (fr.fatallyFailedBlocks / (double) total * 100);

			addChild("div", new String[] { "class", "style" }, new String[] { "progressbar-done", "width: " + fetchedPercent + "%;" });

			if (fr.failedBlocks > 0) addChild("div", new String[] { "class", "style" }, new String[] { "progressbar-failed", "width: " + failedPercent + "%;" });
			if (fr.fatallyFailedBlocks > 0) addChild("div", new String[] { "class", "style" }, new String[] { "progressbar-failed2", "width: " + fatallyFailedPercent + "%;" });

			NumberFormat nf = NumberFormat.getInstance();
			nf.setMaximumFractionDigits(1);
			String prefix = '(' + Integer.toString(fr.fetchedBlocks) + "/ " + Integer.toString(total) + "): ";
			if (fr.finalizedBlocks) {
				addChild("div", new String[] { "class", "title" }, new String[] { "progress_fraction_finalized", prefix + L10n.getString("QueueToadlet.progressbarAccurate") }, nf.format((int) ((fr.fetchedBlocks / (double) total) * 1000) / 10.0) + '%');
			} else {
				String text = nf.format((int) ((fr.fetchedBlocks / (double) total) * 1000) / 10.0) + '%';
				text = "" + fr.fetchedBlocks + " (" + text + "??)";
				addChild("div", new String[] { "class", "title" }, new String[] { "progress_fraction_not_finalized", prefix + L10n.getString("QueueToadlet.progressbarNotAccurate") }, text);
			}
		}
		if(waiter!=null){
			tracker.getFetchInProgress(key, maxSize).close(waiter);
		}
		if(fr!=null){
			tracker.getFetchInProgress(key, maxSize).close(fr);
		}
	}

	@Override
	public String getUpdaterId(String requestId) {
		return getId(key);
	}

	public static String getId(FreenetURI uri) {
		return Base64.encodeStandard(("progressbar[URI:" + uri.toString() + "]").getBytes());
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
	public String getUpdaterType() {
		return UpdaterConstants.PROGRESSBAR_UPDATER;
	}

}
