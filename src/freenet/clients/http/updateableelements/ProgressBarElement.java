package freenet.clients.http.updateableelements;

import java.text.NumberFormat;

import freenet.client.FetchContext;
import freenet.clients.http.FProxyFetchInProgress;
import freenet.clients.http.FProxyFetchResult;
import freenet.clients.http.FProxyFetchTracker;
import freenet.clients.http.FProxyFetchWaiter;
import freenet.clients.http.SimpleToadletServer;
import freenet.clients.http.ToadletContext;
import freenet.keys.FreenetURI;
import freenet.l10n.NodeL10n;
import freenet.support.Base64;
import freenet.support.HTMLNode;

/** A pushed element that renders the progress bar when loading a page. */
public class ProgressBarElement extends BaseUpdateableElement {

	/** The tracker that the Fetcher can be acquired */
	private final FProxyFetchTracker		tracker;
	/** The URI of the download this progress bar shows */
	private final FreenetURI				key;
	/** The maxSize */
	private final long					maxSize;
	/** The FetchListener that gets notified when the download progresses */
	private final NotifierFetchListener	fetchListener;
	private final FetchContext		fctx;

	public ProgressBarElement(FProxyFetchTracker tracker, FreenetURI key, FetchContext fctx, long maxSize, ToadletContext ctx, boolean pushed) {
		// This is a <div>
		super("div", "class", "progressbar", ctx);
		this.tracker = tracker;
		this.key = key;
		this.fctx = fctx;
		this.maxSize = maxSize;
		init(pushed);
		if(!pushed) {
			fetchListener = null;
			return;
		}
		// Creates and registers the FetchListener
		fetchListener = new NotifierFetchListener(((SimpleToadletServer) ctx.getContainer()).pushDataManager, this);
		tracker.getFetchInProgress(key, maxSize, fctx).addListener(fetchListener);
	}

	@Override
	public void updateState(boolean initial) {
		children.clear();

		FProxyFetchInProgress progress = tracker.getFetchInProgress(key, maxSize, fctx);
		FProxyFetchWaiter waiter = progress == null ? null : progress.getWaiter();
		FProxyFetchResult fr = waiter == null ? null : waiter.getResult();
		if (fr == null) {
			addChild("div", "No fetcher found");
		} else {
			if (fr.isFinished() || fr.hasData() || fr.failed != null) {
				// If finished then we just send a FINISHED text. It will reload the page
				setContent(UpdaterConstants.FINISHED);
			} else {
				int total = fr.requiredBlocks;
				int fetchedPercent = (int) (fr.fetchedBlocks / (double) total * 100);
				int failedPercent = (int) (fr.failedBlocks / (double) total * 100);
				int fatallyFailedPercent = (int) (fr.fatallyFailedBlocks / (double) total * 100);
				HTMLNode progressBar = addChild("div", "class", "progressbar");
				progressBar.addChild("div", new String[] { "class", "style" }, new String[] { "progressbar-done", "width: " + fetchedPercent + "%;" });
				
				if (fr.failedBlocks > 0)
					progressBar.addChild("div", new String[] { "class", "style" }, new String[] { "progressbar-failed", "width: " + failedPercent + "%;" });
				if (fr.fatallyFailedBlocks > 0)
					progressBar.addChild("div", new String[] { "class", "style" }, new String[] { "progressbar-failed2", "width: " + fatallyFailedPercent + "%;" });
				
				NumberFormat nf = NumberFormat.getInstance();
				nf.setMaximumFractionDigits(1);
				String prefix = '('+Integer.toString(fr.fetchedBlocks) + "/ " + Integer.toString(total)+"): ";
				if (fr.finalizedBlocks) {
					progressBar.addChild("div", new String[] { "class", "title" }, new String[] { "progress_fraction_finalized", prefix + NodeL10n.getBase().getString("QueueToadlet.progressbarAccurate") }, nf.format((int) ((fr.fetchedBlocks / (double) total) * 1000) / 10.0) + '%');
				} else {
					String text = nf.format((int) ((fr.fetchedBlocks / (double) total) * 1000) / 10.0)+ '%';
					text = "" + fr.fetchedBlocks + " ("+text+"??)";
					progressBar.addChild("div", new String[] { "class", "title" }, new String[] { "progress_fraction_not_finalized", prefix + NodeL10n.getBase().getString("QueueToadlet.progressbarNotAccurate") }, text);
				}
			}
		}
		if (waiter != null) {
			progress.close(waiter);
		}
		if (fr != null) {
			progress.close(fr);
		}
	}

	@Override
	public String getUpdaterId(String requestId) {
		return getId(key);
	}

	public static String getId(FreenetURI uri) {
		return Base64.encodeStandardUTF8(("progressbar[URI:" + uri.toString() + "]"));
	}

	@Override
	public void dispose() {
		// Deregisters the FetchListener
		FProxyFetchInProgress progress = tracker.getFetchInProgress(key, maxSize, fctx);
		if (progress != null) {
			progress.removeListener(fetchListener);
		}
	}

	@Override
	public String getUpdaterType() {
		return UpdaterConstants.PROGRESSBAR_UPDATER;
	}

	@Override
	public String toString() {
		return "ProgressBarElement[key:" + key + ",maxSize:" + maxSize + ",updaterId:" + getUpdaterId(null) + "]";
	}

}
