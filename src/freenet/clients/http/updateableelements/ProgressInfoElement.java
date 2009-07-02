package freenet.clients.http.updateableelements;

import java.text.NumberFormat;

import freenet.clients.http.FProxyFetchInProgress;
import freenet.clients.http.FProxyFetchListener;
import freenet.clients.http.FProxyFetchResult;
import freenet.clients.http.FProxyFetchTracker;
import freenet.clients.http.FProxyToadlet;
import freenet.clients.http.SimpleToadletServer;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.complexhtmlnodes.SecondCounterNode;
import freenet.keys.FreenetURI;
import freenet.l10n.L10n;
import freenet.support.Base64;
import freenet.support.SizeUtil;

public class ProgressInfoElement extends BaseUpdateableElement {

	private FProxyFetchTracker		tracker;
	private FreenetURI				key;
	private long					maxSize;
	private NotifierFetchListener	fetchListener;
	private boolean isAdvancedMode;

	public ProgressInfoElement(FProxyFetchTracker tracker, FreenetURI key, long maxSize,boolean isAdvancedMode, ToadletContext ctx) {
		super("span", ctx);
		this.tracker = tracker;
		this.key = key;
		this.maxSize = maxSize;
		this.isAdvancedMode=isAdvancedMode;
		init();
		fetchListener = new NotifierFetchListener(((SimpleToadletServer) ctx.getContainer()).pushDataManager, this);
		tracker.getFetchInProgress(key, maxSize).addListener(fetchListener);
	}

	@Override
	public void updateState() {
		children.clear();

		FProxyFetchResult fr = tracker.getFetcher(key, maxSize).getResult();
		if (fr == null) {
			addChild("div", "No fetcher found");
		}
		addChild("#", FProxyToadlet.l10n("filenameLabel")+ " ");
		addChild("a", "href", "/"+key.toString(false, false), key.getPreferredFilename());
		if(fr.mimeType != null) addChild("br", FProxyToadlet.l10n("contentTypeLabel")+" "+fr.mimeType);
		if(fr.size > 0) addChild("br", "Size: "+SizeUtil.formatSize(fr.size));
		if(isAdvancedMode) {
			addChild("br", FProxyToadlet.l10n("blocksDetail", 
					new String[] { "fetched", "required", "total", "failed", "fatallyfailed" },
					new String[] { Integer.toString(fr.fetchedBlocks), Integer.toString(fr.requiredBlocks), Integer.toString(fr.totalBlocks), Integer.toString(fr.failedBlocks), Integer.toString(fr.fatallyFailedBlocks) }));
		}
		addChild("br");
		addChild(new SecondCounterNode(System.currentTimeMillis() - fr.timeStarted, true, FProxyToadlet.l10n("timeElapsedLabel")+" "));
		long eta = fr.eta;
		if(eta > 0){
			addChild("br");
			addChild(new SecondCounterNode(eta, false, "ETA: "));
		}
		if(fr.goneToNetwork)
			addChild("p", FProxyToadlet.l10n("progressDownloading"));
		else
			addChild("p", FProxyToadlet.l10n("progressCheckingStore"));
		if(!fr.finalizedBlocks)
			addChild("p", FProxyToadlet.l10n("progressNotFinalized"));
	}

	@Override
	public String getUpdaterId(String requestId) {
		return getId(key);
	}

	public static String getId(FreenetURI uri) {
		return Base64.encodeStandard(("progressinfo[URI:" + uri.toString() + "]").getBytes());
	}

	@Override
	public void dispose() {
		FProxyFetchInProgress progress = tracker.getFetchInProgress(key, maxSize);
		if (progress != null) {
			progress.removeListener(fetchListener);
		}
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
			pushManager.updateElement(element.getUpdaterId(null));
		}
	}

}
