package freenet.clients.http;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.text.NumberFormat;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import freenet.client.DefaultMIMETypes;
import freenet.client.HighLevelSimpleClient;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.node.RequestStarter;
import freenet.node.fcp.ClientGet;
import freenet.node.fcp.ClientPut;
import freenet.node.fcp.ClientPutDir;
import freenet.node.fcp.ClientPutMessage;
import freenet.node.fcp.ClientRequest;
import freenet.node.fcp.FCPServer;
import freenet.node.fcp.IdentifierCollisionException;
import freenet.node.fcp.MessageInvalidException;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.SizeUtil;
import freenet.support.URLEncoder;
import freenet.support.io.Bucket;
import freenet.support.io.BucketTools;
import freenet.support.io.FileBucket;

public class QueueToadlet extends Toadlet {

	private static final String[] priorityClasses = new String[] { "emergency", "very high", "high", "medium", "low", "very low", "will never finish" };

	private static final int LIST_IDENTIFIER = 1;
	private static final int LIST_SIZE = 2;
	private static final int LIST_MIME_TYPE = 3;
	private static final int LIST_DOWNLOAD = 4;
	private static final int LIST_PERSISTENCE = 5;
	private static final int LIST_KEY = 6;
	private static final int LIST_FILENAME = 7;
	private static final int LIST_PRIORITY = 8;
	private static final int LIST_FILES = 9;
	private static final int LIST_TOTAL_SIZE = 10;
	private static final int LIST_PROGRESS = 11;
	private static final int LIST_REASON = 12;
	
	private Node node;
	final FCPServer fcp;
	
	public QueueToadlet(Node n, FCPServer fcp, HighLevelSimpleClient client) {
		super(client);
		this.node = n;
		this.fcp = fcp;
		if(fcp == null) throw new NullPointerException();
	}
	
	public void handlePost(URI uri, Bucket data, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		HTTPRequest request = new HTTPRequest(uri, data, ctx);
		try {
			if ((data.size() > 1024 * 1024) && (request.getPartAsString("insert", 128).length() == 0)) {
				this.writeReply(ctx, 400, "text/plain", "Too big", "Data exceeds 1MB limit");
				return;
			}
	
			String pass = request.getParam("formPassword");
			if (pass.length() == 0) {
				pass = request.getPartAsString("formPassword", 128);
			}
			if ((pass.length() == 0) || !pass.equals(node.formPassword)) {
				MultiValueTable headers = new MultiValueTable();
				headers.put("Location", "/queue/");
				ctx.sendReplyHeaders(302, "Found", headers, null, 0);
				return;
			}

			if(request.isParameterSet("remove_request") && (request.getParam("remove_request").length() > 0)) {
				String identifier = request.getParam("identifier");
				Logger.minor(this, "Removing "+identifier);
				try {
					fcp.removeGlobalRequest(identifier);
				} catch (MessageInvalidException e) {
					this.sendErrorPage(ctx, 200, "Failed to remove request", "Failed to remove " + identifier + ": " + e.getMessage());
				}
				writePermanentRedirect(ctx, "Done", "/queue/");
				return;
			}else if(request.isParameterSet("remove_AllRequests") && (request.getParam("remove_AllRequests").length() > 0)) {
				
				ClientRequest[] reqs = fcp.getGlobalRequests();
				Logger.minor(this, "Request count: "+reqs.length);
				
				for(int i=0; i<reqs.length ; i++){
					String identifier = reqs[i].getIdentifier();
					Logger.minor(this, "Removing "+identifier);
					try {
						fcp.removeGlobalRequest(identifier);
					} catch (MessageInvalidException e) {
						this.sendErrorPage(ctx, 200, "Failed to remove request", "Failed to remove " + identifier + ": " + e.getMessage());
					}
				}
				writePermanentRedirect(ctx, "Done", "/queue/");
				return;
			}else if(request.isParameterSet("download")) {
				// Queue a download
				if(!request.isParameterSet("key")) {
					writeError("No key specified to download", "You did not specify a key to download.", ctx);
					return;
				}
				String expectedMIMEType = null;
				if(request.isParameterSet("type")) {
					expectedMIMEType = request.getParam("type");
				}
				FreenetURI fetchURI;
				try {
					fetchURI = new FreenetURI(request.getParam("key"));
				} catch (MalformedURLException e) {
					writeError("Invalid URI to download", "The URI is invalid and can not be downloaded.", ctx);
					return;
				}
				String persistence = request.getParam("persistence");
				String returnType = request.getParam("return-type");
				fcp.makePersistentGlobalRequest(fetchURI, expectedMIMEType, persistence, returnType);
				writePermanentRedirect(ctx, "Done", "/queue/");
				return;
			} else if (request.isParameterSet("change_priority")) {
				String identifier = request.getParam("identifier");
				short newPriority = Short.parseShort(request.getParam("priority"));
				ClientRequest[] clientRequests = fcp.getGlobalRequests();
				for (int requestIndex = 0, requestCount = clientRequests.length; requestIndex < requestCount; requestIndex++) {
					ClientRequest clientRequest = clientRequests[requestIndex];
					if (clientRequest.getIdentifier().equals(identifier)) {
						clientRequest.setPriorityClass(newPriority);
					}
				}
				writePermanentRedirect(ctx, "Done", "/queue/");
				return;
			} else if (request.getPartAsString("insert", 128).length() > 0) {
				FreenetURI insertURI;
				String keyType = request.getPartAsString("keytype", 3);
				if ("chk".equals(keyType)) {
					insertURI = new FreenetURI("CHK@");
				} else if ("ksk".equals(keyType)) {
					try {
						insertURI = new FreenetURI(request.getPartAsString("key", 128));
					} catch (MalformedURLException mue1) {
						writeError("Invalid URI to insert", "You did not specify a valid URI to insert the file to.", ctx);
						return;
					}
				} else {
					writeError("Invalid URI to insert", "You fooled around with the POST request. Shame on you.", ctx);
					return;
				}
				HTTPRequest.File file = request.getUploadedFile("filename");
				if (file.getFilename().trim().length() == 0) {
					writeError("No file selected", "You did not select a file to upload.", ctx);
					return;
				}
				boolean dontCompress = request.getPartAsString("dontCompress", 128).length() > 0;
				String identifier = file.getFilename() + "-fred-" + System.currentTimeMillis();
				/* copy bucket data */
				Bucket copiedBucket = node.persistentEncryptedTempBucketFactory.makeBucket(file.getData().size());
				BucketTools.copy(file.getData(), copiedBucket);
				try {
					ClientPut clientPut = new ClientPut(fcp.getGlobalClient(), insertURI, identifier, Integer.MAX_VALUE, RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS, ClientRequest.PERSIST_FOREVER, null, false, dontCompress, -1, ClientPutMessage.UPLOAD_FROM_DIRECT, new File(file.getFilename()), file.getContentType(), copiedBucket, null);
					clientPut.start();
					fcp.forceStorePersistentRequests();
				} catch (IdentifierCollisionException e) {
					e.printStackTrace();
				}
				writePermanentRedirect(ctx, "Done", "/queue/");
				return;
			} else if (request.isParameterSet("insert-local")) {
				String filename = request.getParam("filename");
				File file = new File(filename);
				String identifier = file.getName() + "-fred-" + System.currentTimeMillis();
				String contentType = DefaultMIMETypes.guessMIMEType(filename);
				try {
					ClientPut clientPut = new ClientPut(fcp.getGlobalClient(), new FreenetURI("CHK@"), identifier, Integer.MAX_VALUE, RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS, ClientRequest.PERSIST_FOREVER, null, false, false, -1, ClientPutMessage.UPLOAD_FROM_DISK, file, contentType, new FileBucket(file, true, false, false, false), null);
					clientPut.start();
					fcp.forceStorePersistentRequests();
				} catch (IdentifierCollisionException e) {
					e.printStackTrace();
				}
				writePermanentRedirect(ctx, "Done", "/queue/");
				return;
			}
		} finally {
			request.freeParts();
		}
		this.handleGet(uri, ctx);
	}
	
	private void writeError(String header, String message, ToadletContext context) throws ToadletContextClosedException, IOException {
		PageMaker pageMaker = context.getPageMaker();
		HTMLNode pageNode = pageMaker.getPageNode("Error process request");
		HTMLNode contentNode = pageMaker.getContentNode(pageNode);
		contentNode.addChild(node.alerts.createSummary());
		HTMLNode infobox = contentNode.addChild(pageMaker.getInfobox("infobox-error", "Error process request"));
		HTMLNode infoboxContent = pageMaker.getContentNode(infobox);
		infoboxContent.addChild("#", message);
		writeReply(context, 400, "text/html; charset=utf-8", "Error", pageNode.generate());
	}

	public void handleGet(URI uri, ToadletContext ctx) 
	throws ToadletContextClosedException, IOException, RedirectException {
		
		// We ensure that we have a FCP server running
		if(!fcp.enabled){
			this.writeReply(ctx, 400, "text/plain", "FCP server is missing", "You need to enable the FCP server to access this page");
			return;
		}
		
		PageMaker pageMaker = ctx.getPageMaker();
		// First, get the queued requests, and separate them into different types.
		
		LinkedList completedDownloadToDisk = new LinkedList();
		LinkedList completedDownloadToTemp = new LinkedList();
		LinkedList completedUpload = new LinkedList();
		LinkedList completedDirUpload = new LinkedList();
		
		LinkedList failedDownload = new LinkedList();
		LinkedList failedUpload = new LinkedList();
		LinkedList failedDirUpload = new LinkedList();
		
		LinkedList uncompletedDownload = new LinkedList();
		LinkedList uncompletedUpload = new LinkedList();
		LinkedList uncompletedDirUpload = new LinkedList();
		
		ClientRequest[] reqs = fcp.getGlobalRequests();
		Logger.minor(this, "Request count: "+reqs.length);
		
		if(reqs.length < 1){
			HTMLNode pageNode = pageMaker.getPageNode("Global queue of " + node.getMyName());
			HTMLNode contentNode = pageMaker.getContentNode(pageNode);
			HTMLNode infobox = contentNode.addChild(pageMaker.getInfobox("infobox-information", "Global queue is empty"));
			HTMLNode infoboxContent = pageMaker.getContentNode(infobox);
			infoboxContent.addChild("#", "There is no task queued on the global queue at the moment.");
			contentNode.addChild(createInsertBox(pageMaker));
			writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
			return;
		}

		for(int i=0;i<reqs.length;i++) {
			ClientRequest req = reqs[i];
			if(req instanceof ClientGet) {
				ClientGet cg = (ClientGet) req;
				if(cg.hasSucceeded()) {
					if(cg.isDirect())
						completedDownloadToTemp.add(cg);
					else if(cg.isToDisk())
						completedDownloadToDisk.add(cg);
					else
						// FIXME
						Logger.error(this, "Don't know what to do with "+cg);
				} else if(cg.hasFinished()) {
					failedDownload.add(cg);
				} else {
					uncompletedDownload.add(cg);
				}
			} else if(req instanceof ClientPut) {
				ClientPut cp = (ClientPut) req;
				if(cp.hasSucceeded()) {
					completedUpload.add(cp);
				} else if(cp.hasFinished()) {
					failedUpload.add(cp);
				} else {
					uncompletedUpload.add(cp);
				}
			} else if(req instanceof ClientPutDir) {
				ClientPutDir cp = (ClientPutDir) req;
				if(cp.hasSucceeded()) {
					completedDirUpload.add(cp);
				} else if(cp.hasFinished()) {
					failedDirUpload.add(cp);
				} else {
					uncompletedDirUpload.add(cp);
				}
			}
		}
		
		Comparator identifierComparator = new Comparator() {
			public int compare(Object first, Object second) {
				ClientRequest firstRequest = (ClientRequest) first;
				ClientRequest secondRequest = (ClientRequest) second;
				return firstRequest.getIdentifier().compareTo(secondRequest.getIdentifier());
			}
		};
		
		Collections.sort(completedDownloadToDisk, identifierComparator);
		Collections.sort(completedDownloadToTemp, identifierComparator);
		Collections.sort(completedUpload, identifierComparator);
		Collections.sort(completedDirUpload, identifierComparator);
		Collections.sort(failedDownload, identifierComparator);
		Collections.sort(failedUpload, identifierComparator);
		Collections.sort(failedDirUpload, identifierComparator);
		Collections.sort(uncompletedDownload, identifierComparator);
		Collections.sort(uncompletedUpload, identifierComparator);
		Collections.sort(uncompletedDirUpload, identifierComparator);
		
		HTMLNode pageNode = pageMaker.getPageNode("(" + (uncompletedDirUpload.size() + uncompletedDownload.size()
				+ uncompletedUpload.size()) + "/" + (failedDirUpload.size() + failedDownload.size() + failedUpload.size()) + "/"
				+ (completedDirUpload.size() + completedDownloadToDisk.size() + completedDownloadToTemp.size()
				+ completedUpload.size()) + ") Queued Requests of " + node.getMyName());
		HTMLNode contentNode = pageMaker.getContentNode(pageNode);

		/* add alert summary box */
		contentNode.addChild(node.alerts.createSummary());
		/* add file insert box */
		contentNode.addChild(createInsertBox(pageMaker));

		/* navigation bar */
		HTMLNode navigationBar = pageMaker.getInfobox("navbar", "Request Navigation");
		HTMLNode navigationContent = pageMaker.getContentNode(navigationBar).addChild("ul");
		boolean includeNavigationBar = false;
		if (!completedDownloadToTemp.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#completedDownloads", "Completed downloads to temp");
			includeNavigationBar = true;
		}
		if (!completedDownloadToDisk.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#completedDownloadToDisk", "Completed downloads to disk");
			includeNavigationBar = true;
		}
		if (!completedUpload.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#completedUpload", "Completed uploads");
			includeNavigationBar = true;
		}
		if (!completedDirUpload.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#completeDirUpload", "Completed directory uploads");
			includeNavigationBar = true;
		}
		if (!failedDownload.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#failedDownload", "Failed downloads");
			includeNavigationBar = true;
		}
		if (!failedUpload.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#failedUpload", "Failed uploads");
			includeNavigationBar = true;
		}
		if (!failedDirUpload.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#failedDirUpload", "Failed directory uploads");
			includeNavigationBar = true;
		}
		if (!uncompletedDownload.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#uncompletedDownload", "Downloads in progress");
			includeNavigationBar = true;
		}
		if (!uncompletedUpload.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#uncompletedUpload", "Uploads in progress");
			includeNavigationBar = true;
		}
		if (!uncompletedDirUpload.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#uncompletedDirUpload", "Directory uploads in progress");
			includeNavigationBar = true;
		}

		if (includeNavigationBar) {
			contentNode.addChild(navigationBar);
		}

		
		HTMLNode legendBox = contentNode.addChild(pageMaker.getInfobox("legend", "Legend"));
		HTMLNode legendContent = pageMaker.getContentNode(legendBox);
		HTMLNode legendTable = legendContent.addChild("table", "class", "queue");
		HTMLNode legendRow = legendTable.addChild("tr");
		for(int i=0; i<7; i++){
			legendRow.addChild("td", "class", "priority" + i, "Priority " + i);
		}

		if (reqs.length > 1) {
			contentNode.addChild(createPanicBox(pageMaker));
		}

		boolean advancedEnabled = node.getToadletContainer().isAdvancedDarknetEnabled();
		
		if (!completedDownloadToTemp.isEmpty()) {
			contentNode.addChild("a", "name", "completedDownloadToTemp");
			HTMLNode completedDownloadsTempInfobox = contentNode.addChild(pageMaker.getInfobox("completed_requests", "Completed: Downloads to temporary directory (" + completedDownloadToTemp.size() + ")"));
			HTMLNode completedDownloadsToTempContent = pageMaker.getContentNode(completedDownloadsTempInfobox);
			if (advancedEnabled) {
				completedDownloadsToTempContent.addChild(createRequestTable(pageMaker, completedDownloadToTemp, new int[] { LIST_IDENTIFIER, LIST_SIZE, LIST_MIME_TYPE, LIST_DOWNLOAD, LIST_PERSISTENCE, LIST_KEY }));
			} else {
				completedDownloadsToTempContent.addChild(createRequestTable(pageMaker, completedDownloadToTemp, new int[] { LIST_SIZE, LIST_MIME_TYPE, LIST_DOWNLOAD, LIST_PERSISTENCE, LIST_KEY }));
			}
		}
		
		if (!completedDownloadToDisk.isEmpty()) {
			contentNode.addChild("a", "name", "completedDownloadToDisk");
			HTMLNode completedToDiskInfobox = contentNode.addChild(pageMaker.getInfobox("completed_requests", "Completed: Downloads to download directory (" + completedDownloadToDisk.size() + ")"));
			HTMLNode completedToDiskInfoboxContent = pageMaker.getContentNode(completedToDiskInfobox);
			if (advancedEnabled) {
				completedToDiskInfoboxContent.addChild(createRequestTable(pageMaker, completedDownloadToDisk, new int[] { LIST_IDENTIFIER, LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_DOWNLOAD, LIST_PERSISTENCE, LIST_KEY }));
			} else {
				completedToDiskInfoboxContent.addChild(createRequestTable(pageMaker, completedDownloadToDisk, new int[] { LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_DOWNLOAD, LIST_PERSISTENCE, LIST_KEY }));
			}
		}

		if (!completedUpload.isEmpty()) {
			contentNode.addChild("a", "name", "completedUpload");
			HTMLNode completedUploadInfobox = contentNode.addChild(pageMaker.getInfobox("completed_requests", "Completed: Uploads (" + completedUpload.size() + ")"));
			HTMLNode completedUploadInfoboxContent = pageMaker.getContentNode(completedUploadInfobox);
			if (advancedEnabled) {
				completedUploadInfoboxContent.addChild(createRequestTable(pageMaker, completedUpload, new int[] { LIST_IDENTIFIER, LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_PERSISTENCE, LIST_KEY }));
			} else  {
				completedUploadInfoboxContent.addChild(createRequestTable(pageMaker, completedUpload, new int[] { LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_PERSISTENCE, LIST_KEY }));
			}
		}
		
		if (!completedDirUpload.isEmpty()) {
			contentNode.addChild("a", "name", "completedDirUpload");
			HTMLNode completedUploadDirInfobox = contentNode.addChild(pageMaker.getInfobox("completed_requests", "Completed: Directory Uploads (" + completedDirUpload.size() + ")"));
			HTMLNode completedUploadDirContent = pageMaker.getContentNode(completedUploadDirInfobox);
			if (advancedEnabled) {
				completedUploadDirContent.addChild(createRequestTable(pageMaker, completedDirUpload, new int[] { LIST_IDENTIFIER, LIST_FILES, LIST_TOTAL_SIZE, LIST_PERSISTENCE, LIST_KEY }));
			} else {
				completedUploadDirContent.addChild(createRequestTable(pageMaker, completedDirUpload, new int[] { LIST_FILES, LIST_TOTAL_SIZE, LIST_PERSISTENCE, LIST_KEY }));
			}
		}
				
		if (!failedDownload.isEmpty()) {
			contentNode.addChild("a", "name", "failedDownload");
			HTMLNode failedInfobox = contentNode.addChild(pageMaker.getInfobox("failed_requests", "Failed: Downloads (" + failedDownload.size() + ")"));
			HTMLNode failedContent = pageMaker.getContentNode(failedInfobox);
			if (advancedEnabled) {
				failedContent.addChild(createRequestTable(pageMaker, failedDownload, new int[] { LIST_IDENTIFIER, LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_PROGRESS, LIST_REASON, LIST_PERSISTENCE, LIST_KEY }));
			} else {
				failedContent.addChild(createRequestTable(pageMaker, failedDownload, new int[] { LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_PROGRESS, LIST_REASON, LIST_PERSISTENCE, LIST_KEY }));
			}
		}
		
		if (!failedUpload.isEmpty()) {
			contentNode.addChild("a", "name", "failedUpload");
			HTMLNode failedInfobox = contentNode.addChild(pageMaker.getInfobox("failed_requests", "Failed: Uploads (" + failedUpload.size() + ")"));
			HTMLNode failedContent = pageMaker.getContentNode(failedInfobox);
			if (advancedEnabled) {
				failedContent.addChild(createRequestTable(pageMaker, failedUpload, new int[] { LIST_IDENTIFIER, LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_PROGRESS, LIST_REASON, LIST_PERSISTENCE, LIST_KEY }));
			} else {
				failedContent.addChild(createRequestTable(pageMaker, failedUpload, new int[] { LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_PROGRESS, LIST_REASON, LIST_PERSISTENCE, LIST_KEY }));
			}
		}
		
		if (!failedDirUpload.isEmpty()) {
			contentNode.addChild("a", "name", "failedDirUpload");
			HTMLNode failedInfobox = contentNode.addChild(pageMaker.getInfobox("failed_requests", "Failed: Directory Uploads (" + failedDirUpload.size() + ")"));
			HTMLNode failedContent = pageMaker.getContentNode(failedInfobox);
			if (advancedEnabled) {
				failedContent.addChild(createRequestTable(pageMaker, failedDirUpload, new int[] { LIST_IDENTIFIER, LIST_FILES, LIST_TOTAL_SIZE, LIST_PROGRESS, LIST_REASON, LIST_PERSISTENCE, LIST_KEY }));
			} else {
				failedContent.addChild(createRequestTable(pageMaker, failedDirUpload, new int[] { LIST_FILES, LIST_TOTAL_SIZE, LIST_PROGRESS, LIST_REASON, LIST_PERSISTENCE, LIST_KEY }));
			}
		}
		
		if (!uncompletedDownload.isEmpty()) {
			contentNode.addChild("a", "name", "uncompletedDownload");
			HTMLNode uncompletedInfobox = contentNode.addChild(pageMaker.getInfobox("requests_in_progress", "In Progress: Downloads (" + uncompletedDownload.size() + ")"));
			HTMLNode uncompletedContent = pageMaker.getContentNode(uncompletedInfobox);
			if (advancedEnabled) {
				uncompletedContent.addChild(createRequestTable(pageMaker, uncompletedDownload, new int[] { LIST_IDENTIFIER, LIST_FILENAME, LIST_PRIORITY, LIST_SIZE, LIST_MIME_TYPE, LIST_PROGRESS, LIST_PERSISTENCE, LIST_KEY }));
			} else {
				uncompletedContent.addChild(createRequestTable(pageMaker, uncompletedDownload, new int[] { LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_PROGRESS, LIST_PERSISTENCE, LIST_KEY }));
			}
		}
		
		if (!uncompletedUpload.isEmpty()) {
			contentNode.addChild("a", "name", "uncompletedUpload");
			HTMLNode uncompletedInfobox = contentNode.addChild(pageMaker.getInfobox("requests_in_progress", "In Progress: Uploads (" + uncompletedUpload.size() + ")"));
			HTMLNode uncompletedContent = pageMaker.getContentNode(uncompletedInfobox);
			if (advancedEnabled) {
				uncompletedContent.addChild(createRequestTable(pageMaker, uncompletedUpload, new int[] { LIST_IDENTIFIER, LIST_FILENAME, LIST_PRIORITY, LIST_SIZE, LIST_MIME_TYPE, LIST_PROGRESS, LIST_PERSISTENCE, LIST_KEY }));
			} else {
				uncompletedContent.addChild(createRequestTable(pageMaker, uncompletedUpload, new int[] { LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_PROGRESS, LIST_PERSISTENCE, LIST_KEY }));
			}
		}
		
		if (!uncompletedDirUpload.isEmpty()) {
			contentNode.addChild("a", "name", "uncompletedDirUpload");
			HTMLNode uncompletedInfobox = contentNode.addChild(pageMaker.getInfobox("requests_in_progress", "In Progress: Downloads (" + uncompletedDownload.size() + ")"));
			HTMLNode uncompletedContent = pageMaker.getContentNode(uncompletedInfobox);
			if (advancedEnabled) {
				uncompletedContent.addChild(createRequestTable(pageMaker, uncompletedDirUpload, new int[] { LIST_IDENTIFIER, LIST_FILES, LIST_PRIORITY, LIST_TOTAL_SIZE, LIST_PROGRESS, LIST_PERSISTENCE, LIST_KEY }));
			} else {
				uncompletedContent.addChild(createRequestTable(pageMaker, uncompletedDirUpload, new int[] { LIST_FILES, LIST_TOTAL_SIZE, LIST_PROGRESS, LIST_PERSISTENCE, LIST_KEY }));
			}
		}
		
		MultiValueTable pageHeaders = new MultiValueTable();
		pageHeaders.put("Refresh", "30; URL=");
		this.writeReply(ctx, 200, "text/html", "OK", pageHeaders, pageNode.generate());
	}

	
	private HTMLNode createReasonCell(String failureReason) {
		HTMLNode reasonCell = new HTMLNode("td", "class", "request-reason");
		if (failureReason == null) {
			reasonCell.addChild("span", "class", "failure_reason_unknown", "unknown");
		} else {
			reasonCell.addChild("span", "class", "failure_reason_is", failureReason);
		}
		return reasonCell;
	}

	private HTMLNode createProgressCell(boolean started, int fetched, int failed, int fatallyFailed, int min, int total, boolean finalized) {
		HTMLNode progressCell = new HTMLNode("td", "class", "request-progress");
		if (!started) {
			progressCell.addChild("#", "STARTING");
			return progressCell;
		}
		
		//double frac = p.getSuccessFraction();
		if (!node.getToadletContainer().isAdvancedDarknetEnabled()) {
			total = min;
		}
		
		if ((fetched < 0) || (total <= 0)) {
			progressCell.addChild("span", "class", "progress_fraction_unknown", "unknown");
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
			if (finalized) {
				progressBar.addChild("div", new String[] { "class", "title" }, new String[] { "progress_fraction_finalized", "finalized" }, nf.format((int) ((fetched / (double) min) * 1000) / 10.0) + "%");
			} else {
				progressBar.addChild("div", new String[] { "class", "title" }, new String[] { "progress_fraction_not_finalized", "not finalized" }, nf.format((int) ((fetched / (double) min) * 1000) / 10.0)+ "%");
			}
		}
		return progressCell;
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
			filenameCell.addChild("span", "class", "filename_none", "none");
		}
		return filenameCell;
	}

	private HTMLNode createPriorityCell(PageMaker pageMaker, String identifier, short priorityClass) {
		HTMLNode priorityCell = new HTMLNode("td", "class", "request-priority nowrap");
		HTMLNode priorityForm = priorityCell.addChild("form", new String[] { "action", "method" }, new String[] { "/queue/", "post" });
		priorityForm.addChild(pageMaker.createFormPasswordInput(node.formPassword));
		priorityForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "identifier", identifier });
		HTMLNode prioritySelect = priorityForm.addChild("select", "name", "priority");
		for (int p = 0; p < RequestStarter.NUMBER_OF_PRIORITY_CLASSES; p++) {
			if (p == priorityClass) {
				prioritySelect.addChild("option", new String[] { "value", "selected" }, new String[] { String.valueOf(p), "selected" }, priorityClasses[p]);
			} else {
				prioritySelect.addChild("option", "value", String.valueOf(p), priorityClasses[p]);
			}
		}
		priorityForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "change_priority", "Change" });
		return priorityCell;
	}

	private HTMLNode createDeleteCell(PageMaker pageMaker, String identifier) {
		HTMLNode deleteNode = new HTMLNode("td", "class", "request-delete");
		HTMLNode deleteForm = deleteNode.addChild("form", new String[] { "action", "method" }, new String[] { "/queue/", "post" });
		deleteForm.addChild(pageMaker.createFormPasswordInput(node.formPassword));
		deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "identifier", identifier });
		deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "remove_request", "Delete" });
		return deleteNode;
	}
	
	private HTMLNode createPanicBox(PageMaker pageMaker) {
		HTMLNode panicBox = pageMaker.getInfobox("infobox-alert", "Panic Button");
		HTMLNode panicForm = pageMaker.getContentNode(panicBox).addChild("form", new String[] { "action", "method" }, new String[] { "/queue/", "post" });
		panicForm.addChild(pageMaker.createFormPasswordInput(node.formPassword));
		panicForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "remove_AllRequests", "Delete everything without confirmation!" });
		return panicBox;
	}
	
	private HTMLNode createIdentifierCell(FreenetURI uri, String identifier) {
		HTMLNode identifierCell = new HTMLNode("td", "class", "request-identifier");
		if (uri != null) {
			identifierCell.addChild("span", "class", "identifier_with_uri").addChild("a", "href", "/" + uri, identifier);
		} else {
			identifierCell.addChild("span", "class", "identifier_without_uri", identifier);
		}
		return identifierCell;
	}

	private HTMLNode createPersistenceCell(boolean persistent, boolean persistentForever) {
		HTMLNode persistenceCell = new HTMLNode("td", "class", "request-persistence");
		if (persistentForever) {
			persistenceCell.addChild("span", "class", "persistence_forever", "forever");
		} else if (persistent) {
			persistenceCell.addChild("span", "class", "persistence_reboot", "reboot");
		} else {
			persistenceCell.addChild("span", "class", "persistence_none", "none");
		}
		return persistenceCell;
	}

	private HTMLNode createDownloadCell(ClientGet p) {
		return new HTMLNode("td", "class", "request-download", "FIXME"); /* TODO */
	}

	private HTMLNode createTypeCell(String type) {
		HTMLNode typeCell = new HTMLNode("td", "class", "request-type");
		if (type != null) {
			typeCell.addChild("span", "class", "mimetype_is", type);
		} else {
			typeCell.addChild("span", "class", "mimetype_unknown", "unknown");
		}
		return typeCell;
	}

	private HTMLNode createSizeCell(long dataSize) {
		HTMLNode sizeCell = new HTMLNode("td", "class", "request-size");
		if (dataSize >= 0) {
			sizeCell.addChild("span", "class", "filesize_is", SizeUtil.formatSize(dataSize));
		} else {
			sizeCell.addChild("span", "class", "filesize_unknown", "unknown");
		}
		return sizeCell;
	}

	private HTMLNode createKeyCell(FreenetURI uri) {
		HTMLNode keyCell = new HTMLNode("td", "class", "request-key");
		if (uri != null) {
			keyCell.addChild("span", "class", "key_is").addChild("a", "href", "/" + uri.toString(false), uri.toShortString());
		} else {
			keyCell.addChild("span", "class", "key_unknown", "unknown");
		}
		return keyCell;
	}
	
	private HTMLNode createInsertBox(PageMaker pageMaker) {
		/* the insert file box */
		HTMLNode insertBox = pageMaker.getInfobox("Insert File");
		HTMLNode insertForm = pageMaker.getContentNode(insertBox).addChild("form", new String[] { "action", "method", "enctype" }, new String[] { ".", "post", "multipart/form-data" });
		insertForm.addChild(pageMaker.createFormPasswordInput(node.formPassword));
		insertForm.addChild("#", "Insert as: ");
		insertForm.addChild("input", new String[] { "type", "name", "value", "checked" }, new String[] { "radio", "keytype", "chk", "checked" });
		insertForm.addChild("#", " CHK \u00a0 ");
		insertForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "radio", "keytype", "ksk" });
		insertForm.addChild("#", " KSK \u00a0 ");
		insertForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "text", "key", "KSK@" });
		insertForm.addChild("#", " \u00a0 File: ");
		insertForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "file", "filename", "" });
		insertForm.addChild("#", " \u00a0 ");
		insertForm.addChild("input", new String[] { "type", "name", "checked" }, new String[] { "checkbox", "dontCompress", "checked" });
		insertForm.addChild("#", " Don\u2019t compress \u00a0 ");
		insertForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "insert", "Insert file" });
		insertForm.addChild("#", " \u00a0 ");
		insertForm.addChild("input", new String[] { "type", "name" }, new String[] { "reset", "Reset form" });
		return insertBox;
	}
	
	private HTMLNode createRequestTable(PageMaker pageMaker, List requests, int[] columns) {
		HTMLNode table = new HTMLNode("table", "class", "requests");
		HTMLNode headerRow = table.addChild("tr", "class", "table-header");
		headerRow.addChild("th");
		for (int columnIndex = 0, columnCount = columns.length; columnIndex < columnCount; columnIndex++) {
			int column = columns[columnIndex];
			if (column == LIST_IDENTIFIER) {
				headerRow.addChild("th", "Identifier");
			} else if (column == LIST_SIZE) {
				headerRow.addChild("th", "Size");
			} else if (column == LIST_DOWNLOAD) {
				headerRow.addChild("th", "Download");
			} else if (column == LIST_MIME_TYPE) {
				headerRow.addChild("th", "MIME Type");
			} else if (column == LIST_PERSISTENCE) {
				headerRow.addChild("th", "Persistence");
			} else if (column == LIST_KEY) {
				headerRow.addChild("th", "Key");
			} else if (column == LIST_FILENAME) {
				headerRow.addChild("th", "Filename");
			} else if (column == LIST_PRIORITY) {
				headerRow.addChild("th", "Priority");
			} else if (column == LIST_FILES) {
				headerRow.addChild("th", "Files");
			} else if (column == LIST_TOTAL_SIZE) {
				headerRow.addChild("th", "Total Size");
			} else if (column == LIST_PROGRESS) {
				headerRow.addChild("th", "Progress");
			} else if (column == LIST_REASON) {
				headerRow.addChild("th", "Reason");
			}
		}
		for (Iterator requestItems = requests.iterator(); requestItems.hasNext(); ) {
			ClientRequest clientRequest = (ClientRequest) requestItems.next();
			HTMLNode requestRow = table.addChild("tr", "class", "priority" + clientRequest.getPriority());
			
			requestRow.addChild(createDeleteCell(pageMaker, clientRequest.getIdentifier()));
			for (int columnIndex = 0, columnCount = columns.length; columnIndex < columnCount; columnIndex++) {
				int column = columns[columnIndex];
				if (column == LIST_IDENTIFIER) {
					if (clientRequest instanceof ClientGet) {
						requestRow.addChild(createIdentifierCell(((ClientGet) clientRequest).getURI(), clientRequest.getIdentifier()));
					} else if (clientRequest instanceof ClientPutDir) {
						requestRow.addChild(createIdentifierCell(((ClientPutDir) clientRequest).getFinalURI(), clientRequest.getIdentifier()));
					} else if (clientRequest instanceof ClientPut) {
						requestRow.addChild(createIdentifierCell(((ClientPut) clientRequest).getFinalURI(), clientRequest.getIdentifier()));
					}
				} else if (column == LIST_SIZE) {
					if (clientRequest instanceof ClientGet) {
						requestRow.addChild(createSizeCell(((ClientGet) clientRequest).getDataSize()));
					} else if (clientRequest instanceof ClientPut) {
						requestRow.addChild(createSizeCell(((ClientPut) clientRequest).getDataSize()));
					}
				} else if (column == LIST_DOWNLOAD) {
					requestRow.addChild(createDownloadCell((ClientGet) clientRequest));
				} else if (column == LIST_MIME_TYPE) {
					if (clientRequest instanceof ClientGet) {
						requestRow.addChild(createTypeCell(((ClientGet) clientRequest).getMIMEType()));
					} else if (clientRequest instanceof ClientPut) {
						requestRow.addChild(createTypeCell(((ClientPut) clientRequest).getMIMEType()));
					}
				} else if (column == LIST_PERSISTENCE) {
					requestRow.addChild(createPersistenceCell(clientRequest.isPersistent(), clientRequest.isPersistentForever()));
				} else if (column == LIST_KEY) {
					if (clientRequest instanceof ClientGet) {
						requestRow.addChild(createKeyCell(((ClientGet) clientRequest).getURI()));
					} else {
						requestRow.addChild(createKeyCell(((ClientPut) clientRequest).getFinalURI()));
					}
				} else if (column == LIST_FILENAME) {
					if (clientRequest instanceof ClientGet) {
						requestRow.addChild(createFilenameCell(((ClientGet) clientRequest).getDestFilename()));
					} else if (clientRequest instanceof ClientPut) {
						requestRow.addChild(createFilenameCell(((ClientPut) clientRequest).getOrigFilename()));
					}
				} else if (column == LIST_PRIORITY) {
					requestRow.addChild(createPriorityCell(pageMaker, clientRequest.getIdentifier(), clientRequest.getPriority()));
				} else if (column == LIST_FILES) {
					requestRow.addChild(createNumberCell(((ClientPutDir) clientRequest).getNumberOfFiles()));
				} else if (column == LIST_TOTAL_SIZE) {
					requestRow.addChild(createSizeCell(((ClientPutDir) clientRequest).getTotalDataSize()));
				} else if (column == LIST_PROGRESS) {
					requestRow.addChild(createProgressCell(clientRequest.isStarted(), (int) clientRequest.getFetchedBlocks(), (int) clientRequest.getFailedBlocks(), (int) clientRequest.getFatalyFailedBlocks(), (int) clientRequest.getMinBlocks(), (int) clientRequest.getTotalBlocks(), clientRequest.isTotalFinalized()));
				} else if (column == LIST_REASON) {
					requestRow.addChild(createReasonCell(clientRequest.getFailureReason()));
				}
			}
		}
		return table;
	}

	public String supportedMethods() {
		return "GET, POST";
	}

}
