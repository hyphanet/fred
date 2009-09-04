package freenet.clients.http.updateableelements;

import java.io.File;
import java.text.NumberFormat;

import com.db4o.ObjectContainer;

import freenet.clients.http.QueueToadlet;
import freenet.clients.http.SimpleToadletServer;
import freenet.clients.http.ToadletContext;
import freenet.keys.FreenetURI;
import freenet.l10n.NodeL10n;
import freenet.node.RequestStarter;
import freenet.node.fcp.ClientGet;
import freenet.node.fcp.ClientPut;
import freenet.node.fcp.ClientPutDir;
import freenet.node.fcp.ClientRequest;
import freenet.node.fcp.FCPServer;
import freenet.node.fcp.ClientPut.COMPRESS_STATE;
import freenet.node.fcp.whiteboard.WhiteboardListener;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.SizeUtil;

/** This element is a row in the download/uploads page.*/
public class RequestElement extends BaseUpdateableElement {

	/** The server*/
	private FCPServer			server;

	/** The id of the request thats information is shown in this row*/
	private String				clientRequestId;

	private int[]				columns;

	private String				path;

	private ObjectContainer		container;

	private boolean				advancedModeEnabled;

	private String[]			priorityClasses;

	private boolean				isUpload;

	/** The listener that gets notified when progress change for this request*/
	private ProgressListener	progressListener;

	/** Was it finished last time? Needed to reload the browser if it just finished*/
	private boolean				wasFinished	= false;
	
	private boolean hasFriends;

	public RequestElement(FCPServer server, ClientRequest clientRequest, int[] columns, String path, ObjectContainer container, boolean advancedModeEnabled, String[] priorityClasses, boolean isUpload, ToadletContext ctx,boolean hasFriends) {
		super("tr", "class", "priority" + clientRequest.getPriority(), ctx);
		this.columns = columns;
		this.path = path;
		this.container = container;
		this.advancedModeEnabled = advancedModeEnabled;
		this.priorityClasses = priorityClasses;
		this.isUpload = isUpload;
		this.server = server;
		this.clientRequestId = clientRequest.getIdentifier();
		this.hasFriends=hasFriends;
		
		progressListener = new ProgressListener(((SimpleToadletServer) ctx.getContainer()).pushDataManager);
		wasFinished = clientRequest.hasFinished();

		init();

		//registers the listener to the Whiteboard
		server.getWhiteboard().addListener(clientRequest.getIdentifier(), progressListener);
	}

	@Override
	public void dispose() {
		server.getWhiteboard().removeListener(progressListener);
	}

	@Override
	public String getUpdaterId(String requestId) {
		return getId(clientRequestId);
	}

	public static String getId(String requestId) {
		return "RequestElement:" + requestId;
	}

	@Override
	public String getUpdaterType() {
		return UpdaterConstants.PROGRESSBAR_UPDATER;
	}

	@Override
	public void updateState(boolean initial) {
		children.clear();

		ClientRequest clientRequest = server.getGlobalRequest(clientRequestId, container);

		if (wasFinished == false && clientRequest.hasFinished()) {
			setContent(UpdaterConstants.FINISHED);
		} else {

			addChild(createDeleteCell(clientRequest.getIdentifier(), clientRequest, ctx, container));

			for (int columnIndex = 0, columnCount = columns.length; columnIndex < columnCount; columnIndex++) {
				int column = columns[columnIndex];
				if (column == QueueToadlet.LIST_IDENTIFIER) {
					if (clientRequest instanceof ClientGet) {
						addChild(createIdentifierCell(((ClientGet) clientRequest).getURI(container), clientRequest.getIdentifier(), false));
					} else if (clientRequest instanceof ClientPutDir) {
						addChild(createIdentifierCell(((ClientPutDir) clientRequest).getFinalURI(container), clientRequest.getIdentifier(), true));
					} else if (clientRequest instanceof ClientPut) {
						addChild(createIdentifierCell(((ClientPut) clientRequest).getFinalURI(container), clientRequest.getIdentifier(), false));
					}
				} else if (column == QueueToadlet.LIST_SIZE) {
					if (clientRequest instanceof ClientGet) {
						addChild(createSizeCell(((ClientGet) clientRequest).getDataSize(container), ((ClientGet) clientRequest).isTotalFinalized(container), advancedModeEnabled));
					} else if (clientRequest instanceof ClientPut) {
						addChild(createSizeCell(((ClientPut) clientRequest).getDataSize(container), true, advancedModeEnabled));
					}
				} else if (column == QueueToadlet.LIST_DOWNLOAD) {
					addChild(createDownloadCell((ClientGet) clientRequest, container));
				} else if (column == QueueToadlet.LIST_MIME_TYPE) {
					if (clientRequest instanceof ClientGet) {
						addChild(createTypeCell(((ClientGet) clientRequest).getMIMEType(container)));
					} else if (clientRequest instanceof ClientPut) {
						addChild(createTypeCell(((ClientPut) clientRequest).getMIMEType()));
					}
				} else if (column == QueueToadlet.LIST_PERSISTENCE) {
					addChild(createPersistenceCell(clientRequest.isPersistent(), clientRequest.isPersistentForever()));
				} else if (column == QueueToadlet.LIST_KEY) {
					if (clientRequest instanceof ClientGet) {
						addChild(createKeyCell(((ClientGet) clientRequest).getURI(container), false));
					} else if (clientRequest instanceof ClientPut) {
						addChild(createKeyCell(((ClientPut) clientRequest).getFinalURI(container), false));
					}else {
						addChild(createKeyCell(((ClientPutDir) clientRequest).getFinalURI(container), true));
					}
				} else if (column == QueueToadlet.LIST_FILENAME) {
					if (clientRequest instanceof ClientGet) {
						addChild(createFilenameCell(((ClientGet) clientRequest).getDestFilename(container)));
					} else if (clientRequest instanceof ClientPut) {
						addChild(createFilenameCell(((ClientPut) clientRequest).getOrigFilename(container)));
					}
				} else if (column == QueueToadlet.LIST_PRIORITY) {
					addChild(createPriorityCell(clientRequest.getIdentifier(), clientRequest.getPriority(), ctx, priorityClasses, advancedModeEnabled));
				} else if (column == QueueToadlet.LIST_FILES) {
					addChild(createNumberCell(((ClientPutDir) clientRequest).getNumberOfFiles()));
				} else if (column == QueueToadlet.LIST_TOTAL_SIZE) {
					addChild(createSizeCell(((ClientPutDir) clientRequest).getTotalDataSize(), true, advancedModeEnabled));
				} else if (column == QueueToadlet.LIST_PROGRESS) {
					if(clientRequest instanceof ClientPut)
						addChild(createProgressCell(clientRequest.isStarted(), ((ClientPut)clientRequest).isCompressing(container), (int) clientRequest.getFetchedBlocks(container), (int) clientRequest.getFailedBlocks(container), (int) clientRequest.getFatalyFailedBlocks(container), (int) clientRequest.getMinBlocks(container), (int) clientRequest.getTotalBlocks(container), clientRequest.isTotalFinalized(container) || clientRequest instanceof ClientPut, isUpload));
					else
						addChild(createProgressCell(clientRequest.isStarted(), COMPRESS_STATE.WORKING, (int) clientRequest.getFetchedBlocks(container), (int) clientRequest.getFailedBlocks(container), (int) clientRequest.getFatalyFailedBlocks(container), (int) clientRequest.getMinBlocks(container), (int) clientRequest.getTotalBlocks(container), clientRequest.isTotalFinalized(container) || clientRequest instanceof ClientPut, isUpload));
				} else if (column == QueueToadlet.LIST_REASON) {
					addChild(createReasonCell(clientRequest.getFailureReason(container)));
				} else if (column == QueueToadlet.LIST_RECOMMEND && hasFriends) {
					if(clientRequest instanceof ClientGet) {
						addChild(createRecommendCell(((ClientGet) clientRequest).getURI(container), ctx));
					} else {
						addChild(createRecommendCell(((ClientPut) clientRequest).getFinalURI(container), ctx));
					}
				}
			}
		}
	}

	private HTMLNode createNumberCell(int numberOfFiles) {
		HTMLNode numberCell = new HTMLNode("td", "class", "request-files");
		numberCell.addChild("span", "class", "number_of_files", String.valueOf(numberOfFiles));
		return numberCell;
	}

	private HTMLNode createFilenameCell(File filename) {
		HTMLNode filenameCell = new HTMLNode("td", "class", "request-filename");
		if (filename != null) {
			filenameCell.addChild("span", "class", "filename_is", filename.toString());
		} else {
			filenameCell.addChild("span", "class", "filename_none", NodeL10n.getBase().getString("QueueToadlet.none"));
		}
		return filenameCell;
	}

	private HTMLNode createPriorityCell(String identifier, short priorityClass, ToadletContext ctx, String[] priorityClasses, boolean advancedModeEnabled) {
		
		HTMLNode priorityCell = new HTMLNode("td", "class", "request-priority nowrap");
		HTMLNode priorityForm = ctx.addFormChild(priorityCell, path, "queueChangePriorityCell-" + identifier.hashCode());
		priorityForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "identifier", identifier });
		HTMLNode prioritySelect = priorityForm.addChild("select", "name", "priority");
		for (int p = 0; p < RequestStarter.NUMBER_OF_PRIORITY_CLASSES; p++) {
			if(p <= RequestStarter.INTERACTIVE_PRIORITY_CLASS && !advancedModeEnabled) continue;
			if (p == priorityClass) {
				prioritySelect.addChild("option", new String[] { "value", "selected" }, new String[] { String.valueOf(p), "selected" }, priorityClasses[p]);
			} else {
				prioritySelect.addChild("option", "value", String.valueOf(p), priorityClasses[p]);
			}
		}
		priorityForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "change_priority", NodeL10n.getBase().getString("QueueToadlet.change") });
		return priorityCell;
	}

	private HTMLNode createRecommendCell(FreenetURI URI, ToadletContext ctx) {
		HTMLNode recommendNode = new HTMLNode("td", "class", "request-delete");
		HTMLNode shareForm = ctx.addFormChild(recommendNode, path, "recommendForm");
		shareForm.addChild("input", new String[] {"type", "name", "value"}, new String[] {"hidden", "URI", URI.toString() });
		shareForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "recommend_request", NodeL10n.getBase().getString("QueueToadlet.recommendToFriends") });

		return recommendNode;
	}

	private HTMLNode createDeleteCell(String identifier, ClientRequest clientRequest, ToadletContext ctx, ObjectContainer container) {
		HTMLNode deleteNode = new HTMLNode("td", "class", "request-delete");
		HTMLNode deleteForm = ctx.addFormChild(deleteNode, path, "queueDeleteForm-" + identifier.hashCode());
		deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "identifier", identifier });
		if((clientRequest instanceof ClientGet) && !((ClientGet)clientRequest).isToDisk()) {
			deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "delete_request", NodeL10n.getBase().getString("QueueToadlet.deleteFileFromTemp") });
			FreenetURI uri = ((ClientGet)clientRequest).getURI(container);
			deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "key", uri.toString(false, false) });
			deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "size", SizeUtil.formatSize(((ClientGet)clientRequest).getDataSize(container)) });
			deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "filename", uri.getPreferredFilename() });
			if(((ClientGet)clientRequest).isTotalFinalized(container))
				deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "finalized", "true" });
		} else
			deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "remove_request", NodeL10n.getBase().getString("QueueToadlet.remove") });
		
		// If it's failed, offer to restart it
		
		if(clientRequest.hasFinished() && !clientRequest.hasSucceeded() && clientRequest.canRestart()) {
			HTMLNode retryForm = ctx.addFormChild(deleteNode, path, "queueRestartForm-" + identifier.hashCode());
			String restartName = NodeL10n.getBase().getString(clientRequest instanceof ClientGet && ((ClientGet)clientRequest).hasPermRedirect() ? "QueueToadlet.follow" : "QueueToadlet.restart");
			retryForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "identifier", identifier });
			retryForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "restart_request", restartName });
		}
		
		return deleteNode;
	}

	private HTMLNode createIdentifierCell(FreenetURI uri, String identifier, boolean directory) {
		HTMLNode identifierCell = new HTMLNode("td", "class", "request-identifier");
		if (uri != null) {
			identifierCell.addChild("span", "class", "identifier_with_uri").addChild("a", "href", "/" + uri + (directory ? "/" : ""), identifier);
		} else {
			identifierCell.addChild("span", "class", "identifier_without_uri", identifier);
		}
		return identifierCell;

	}

	private HTMLNode createPersistenceCell(boolean persistent, boolean persistentForever) {
		HTMLNode persistenceCell = new HTMLNode("td", "class", "request-persistence");
		if (persistentForever) {
			persistenceCell.addChild("span", "class", "persistence_forever", NodeL10n.getBase().getString("QueueToadlet.persistenceForever"));
		} else if (persistent) {
			persistenceCell.addChild("span", "class", "persistence_reboot", NodeL10n.getBase().getString("QueueToadlet.persistenceReboot"));
		} else {
			persistenceCell.addChild("span", "class", "persistence_none", NodeL10n.getBase().getString("QueueToadlet.persistenceNone"));
		}
		return persistenceCell;
	}

	private HTMLNode createDownloadCell(ClientGet p, ObjectContainer container) {
		HTMLNode downloadCell = new HTMLNode("td", "class", "request-download");
		FreenetURI uri = p.getURI(container);
		if(uri == null)
			Logger.error(this, "NO URI FOR "+p, new Exception("error"));
		else
			downloadCell.addChild("a", "href", uri.toString(), NodeL10n.getBase().getString("QueueToadlet.download"));
		return downloadCell;
	}

	private HTMLNode createTypeCell(String type) {
		HTMLNode typeCell = new HTMLNode("td", "class", "request-type");
		if (type != null) {
			typeCell.addChild("span", "class", "mimetype_is", type);
		} else {
			typeCell.addChild("span", "class", "mimetype_unknown", NodeL10n.getBase().getString("QueueToadlet.unknown"));
		}
		return typeCell;
	}

	private HTMLNode createSizeCell(long dataSize, boolean confirmed, boolean advancedModeEnabled) {
		HTMLNode sizeCell = new HTMLNode("td", "class", "request-size");
		if (dataSize > 0 && (confirmed || advancedModeEnabled)) {
			sizeCell.addChild("span", "class", "filesize_is", (confirmed ? "" : ">= ") + SizeUtil.formatSize(dataSize) + (confirmed ? "" : " ??"));
		} else {
			sizeCell.addChild("span", "class", "filesize_unknown", NodeL10n.getBase().getString("QueueToadlet.unknown"));
		}
		return sizeCell;
	}

	private HTMLNode createKeyCell(FreenetURI uri, boolean addSlash) {
		HTMLNode keyCell = new HTMLNode("td", "class", "request-key");
		if (uri != null) {
			keyCell.addChild("span", "class", "key_is").addChild("a", "href", '/' + uri.toString() + (addSlash ? "/" : ""), uri.toShortString() + (addSlash ? "/" : ""));
		} else {
			keyCell.addChild("span", "class", "key_unknown", NodeL10n.getBase().getString("QueueToadlet.unknown"));
		}
		return keyCell;
	}

	private HTMLNode createReasonCell(String failureReason) {
		HTMLNode reasonCell = new HTMLNode("td", "class", "request-reason");
		if (failureReason == null) {
			reasonCell.addChild("span", "class", "failure_reason_unknown", NodeL10n.getBase().getString("QueueToadlet.unknown"));
		} else {
			reasonCell.addChild("span", "class", "failure_reason_is", failureReason);
		}
		return reasonCell;
	}

	private HTMLNode createProgressCell(boolean started, COMPRESS_STATE compressing, int fetched, int failed, int fatallyFailed, int min, int total, boolean finalized, boolean upload) {
		return createProgressCell(advancedModeEnabled, started, compressing, fetched, failed, fatallyFailed, min, total, finalized, upload);
	}
	
	public static HTMLNode createProgressCell(boolean advancedMode, boolean started, COMPRESS_STATE compressing, int fetched, int failed, int fatallyFailed, int min, int total, boolean finalized, boolean upload) {
		HTMLNode progressCell = new HTMLNode("td", "class", "request-progress");
		if (!started) {
			progressCell.addChild("#", NodeL10n.getBase().getString("QueueToadlet.starting"));
			return progressCell;
		}
		if(compressing == COMPRESS_STATE.WAITING && advancedMode) {
			progressCell.addChild("#", NodeL10n.getBase().getString("QueueToadlet.awaitingCompression"));
			return progressCell;
		}
		if(compressing != COMPRESS_STATE.WORKING) {
			progressCell.addChild("#", NodeL10n.getBase().getString("QueueToadlet.compressing"));
			return progressCell;
		}
		
		//double frac = p.getSuccessFraction();
		if (!advancedMode || total < min /* FIXME why? */) {
			total = min;
		}
		
		if ((fetched < 0) || (total <= 0)) {
			progressCell.addChild("span", "class", "progress_fraction_unknown", NodeL10n.getBase().getString("QueueToadlet.unknown"));
		} else {
			int fetchedPercent = (int) (fetched / (double) total * 100);
			int failedPercent = (int) (failed / (double) total * 100);
			int fatallyFailedPercent = (int) (fatallyFailed / (double) total * 100);
			int minPercent = (int) (min / (double) total * 100);
			HTMLNode progressBar = progressCell.addChild("div", "class", "progressbar");
			progressBar.addChild("div", new String[] { "class", "style" }, new String[] { "progressbar-done", "width: " + fetchedPercent + "%;" });

			if (failed > 0)
				progressBar.addChild("div", new String[] { "class", "style" }, new String[] { "progressbar-failed", "width: " + failedPercent + "%;" });
			if (fatallyFailed > 0)
				progressBar.addChild("div", new String[] { "class", "style" }, new String[] { "progressbar-failed2", "width: " + fatallyFailedPercent + "%;" });
			if ((fetched + failed + fatallyFailed) < min)
				progressBar.addChild("div", new String[] { "class", "style" }, new String[] { "progressbar-min", "width: " + (minPercent - fetchedPercent) + "%;" });
			
			NumberFormat nf = NumberFormat.getInstance();
			nf.setMaximumFractionDigits(1);
			String prefix = '('+Integer.toString(fetched) + "/ " + Integer.toString(min)+"): ";
			if (finalized) {
				progressBar.addChild("div", new String[] { "class", "title" }, new String[] { "progress_fraction_finalized", prefix + NodeL10n.getBase().getString("QueueToadlet.progressbarAccurate") }, nf.format((int) ((fetched / (double) min) * 1000) / 10.0) + '%');
			} else {
				String text = nf.format((int) ((fetched / (double) min) * 1000) / 10.0)+ '%';
				if(!finalized)
					text = "" + fetched + " ("+text+"??)";
				progressBar.addChild("div", new String[] { "class", "title" }, new String[] { "progress_fraction_not_finalized", prefix + NodeL10n.getBase().getString(upload ? "QueueToadlet.uploadProgressbarNotAccurate" : "QueueToadlet.progressbarNotAccurate") }, text);
			}
		}
		return progressCell;
	}

	private class ProgressListener implements WhiteboardListener {

		private PushDataManager	manager;

		public ProgressListener(PushDataManager pushDataManager) {
			this.manager = pushDataManager;
		}

		public void onEvent(String id, Object msg) {
			manager.updateElement(getUpdaterId(null));
		}

	}
	
	@Override
	public String toString() {
		return "RequestElement[clientRequestId:"+clientRequestId+"]";
	}
	
	

}
