package freenet.clients.http.updateableelements;

import freenet.client.FetchContext;
import freenet.clients.http.FProxyFetchInProgress;
import freenet.clients.http.FProxyFetchResult;
import freenet.clients.http.FProxyFetchTracker;
import freenet.clients.http.FProxyFetchWaiter;
import freenet.clients.http.FProxyToadlet;
import freenet.clients.http.SimpleToadletServer;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.complexhtmlnodes.SecondCounterNode;
import freenet.keys.FreenetURI;
import freenet.support.Base64;
import freenet.support.HTMLNode;
import freenet.support.SizeUtil;

/** This pushed element renders the information box when a page is downloading on the progress page. */
public class ProgressInfoElement extends BaseUpdateableElement {

	private FProxyFetchTracker		tracker;
	private final FreenetURI		key;
	private final FetchContext		fctx;
	private long					maxSize;
	private NotifierFetchListener	fetchListener;
	/** It displays more info on advanced mode */
	private boolean					isAdvancedMode;

	public ProgressInfoElement(FProxyFetchTracker tracker, FreenetURI key, FetchContext fctx, long maxSize, boolean isAdvancedMode, ToadletContext ctx, boolean pushed) {
		super("span", ctx);
		this.tracker = tracker;
		this.key = key;
		this.fctx = fctx;
		this.maxSize = maxSize;
		this.isAdvancedMode = isAdvancedMode;
		init(pushed);
		if(!pushed) return;
		fetchListener = new NotifierFetchListener(((SimpleToadletServer) ctx.getContainer()).pushDataManager, this);
		tracker.getFetchInProgress(key, maxSize, fctx).addListener(fetchListener);
	}

	@Override
	public void updateState(boolean initial) {
		children.clear();

		FProxyFetchWaiter waiter = tracker.makeWaiterForFetchInProgress(key, maxSize, fctx);
		FProxyFetchResult fr = waiter == null ? null : waiter.getResult();
		if (fr == null) {
			addChild("div", "No fetcher found");
			return;
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
		long elapsed = System.currentTimeMillis() - fr.timeStarted;
		addChild("br");
		addChild(new SecondCounterNode(elapsed, true, FProxyToadlet.l10n("timeElapsedLabel") + " "));
		long eta = fr.eta - elapsed;
		if (eta > 0) {
			addChild("br");
			addChild(new SecondCounterNode(eta, false, "ETA: "));
		}
		if (ctx.getContainer().isFProxyJavascriptEnabled()) {
			HTMLNode lastRefreshNode = new HTMLNode("span", "class", "jsonly");
			lastRefreshNode.addChild("br");
			lastRefreshNode.addChild(new SecondCounterNode(0, true, FProxyToadlet.l10n("lastRefresh")));
			addChild(lastRefreshNode);
		}
		if(fr.goneToNetwork)
			addChild("p", FProxyToadlet.l10n("progressDownloading"));
		else
			addChild("p", FProxyToadlet.l10n("progressCheckingStore"));
		if(!fr.finalizedBlocks)
			addChild("p", FProxyToadlet.l10n("progressNotFinalized"));

		if (waiter != null) {
			tracker.getFetchInProgress(key, maxSize, fctx).close(waiter);
		}
		if (fr != null) {
			tracker.getFetchInProgress(key, maxSize, fctx).close(fr);
		}
	}

	@Override
	public String getUpdaterId(String requestId) {
		return getId(key);
	}

	public static String getId(FreenetURI uri) {
		return Base64.encodeStandardUTF8(("progressinfo[URI:" + uri.toString() + "]"));
	}

	@Override
	public void dispose() {
		FProxyFetchInProgress progress = tracker.getFetchInProgress(key, maxSize, fctx);
		if (progress != null) {
			progress.removeListener(fetchListener);
		}
	}

	@Override
	public String getUpdaterType() {
		return UpdaterConstants.REPLACER_UPDATER;
	}

	@Override
	public String toString() {
		return "ProgressInfoElement[key:" + key + ",maxSize:" + maxSize + ",updaterId:" + getUpdaterId(null) + "]";
	}

}
