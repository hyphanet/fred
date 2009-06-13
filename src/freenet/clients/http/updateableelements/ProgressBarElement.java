package freenet.clients.http.updateableelements;

import java.text.NumberFormat;

import freenet.clients.http.FProxyFetchListener;
import freenet.clients.http.FProxyFetchResult;
import freenet.clients.http.FProxyFetchTracker;
import freenet.clients.http.SimpleToadletServer;
import freenet.clients.http.ToadletContext;
import freenet.keys.FreenetURI;
import freenet.l10n.L10n;
import freenet.support.Base64;

public class ProgressBarElement extends BaseUpdateableElement {

	private FProxyFetchTracker	tracker;
	private FreenetURI			key;
	private long				maxSize;
	private NotifierFetchListener fetchListener; 

	public ProgressBarElement(FProxyFetchTracker tracker, FreenetURI key, long maxSize, String requestUniqueName, ToadletContext ctx) {
		super("div", "class", "progressbar", requestUniqueName, ctx);
		this.tracker = tracker;
		this.key = key;
		this.maxSize = maxSize;
		init();
		fetchListener=new NotifierFetchListener(((SimpleToadletServer) ctx.getContainer()).pushDataManager, this);
		tracker.getFetchInProgress(key, maxSize).addListener(fetchListener);
	}

	@Override
	public void updateState() {
		children.clear();

		FProxyFetchResult fr = tracker.getFetcher(key, maxSize).getResult();
		if (fr == null) {
			addChild("div", "No fetcher found");
		}
		if (fr.isFinished()) {
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
	}

	@Override
	public String getUpdaterId() {
		return getId(key);
	}

	public static String getId(FreenetURI uri) {
		return Base64.encodeStandard(("progressbar[URI:" + uri.toString() + "]").getBytes());
	}
	
	@Override
	public void dispose() {
		tracker.getFetchInProgress(key, maxSize).removeListener(fetchListener);
	}

	@Override
	public String getUpdaterType() {
		return UpdaterConstants.PROGRESSBAR_UPDATER;
	}

	private class NotifierFetchListener implements FProxyFetchListener {

		private PushDataManager			pushManager;

		private BaseUpdateableElement	element;

		private NotifierFetchListener(PushDataManager pushManager, BaseUpdateableElement element) {
			this.pushManager = pushManager;
			this.element = element;
		}

		public void onEvent() {
			pushManager.updateElement(element.getUpdaterId());
		}
	}
}
