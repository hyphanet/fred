/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
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
import freenet.node.NodeClientCore;
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
import freenet.support.api.Bucket;
import freenet.support.api.HTTPRequest;
import freenet.support.api.HTTPUploadedFile;
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

	private static final int MAX_IDENTIFIER_LENGTH = 1024*1024;
	private static final int MAX_FILENAME_LENGTH = 1024*1024;
	private static final int MAX_TYPE_LENGTH = 1024;
	static final int MAX_KEY_LENGTH = 1024*1024;
	
	private NodeClientCore core;
	final FCPServer fcp;
	
	private boolean isReversed = false;
	
	public QueueToadlet(NodeClientCore core, FCPServer fcp, HighLevelSimpleClient client) {
		super(client);
		this.core = core;
		this.fcp = fcp;
		if(fcp == null) throw new NullPointerException();
	}
	
	public void handlePost(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		try {
			// Browse... button
			if (request.getPartAsString("insert-local", 128).length() > 0) {
				MultiValueTable responseHeaders = new MultiValueTable();
				responseHeaders.put("Location", "/files/");
				ctx.sendReplyHeaders(302, "Found", responseHeaders, null, 0);
				return;
			}			
			
			boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
			
			String pass = request.getPartAsString("formPassword", 32);
			if ((pass.length() == 0) || !pass.equals(core.formPassword)) {
				MultiValueTable headers = new MultiValueTable();
				headers.put("Location", "/queue/");
				ctx.sendReplyHeaders(302, "Found", headers, null, 0);
				if(logMINOR) Logger.minor(this, "No formPassword: "+pass);
				return;
			}

			if(request.isPartSet("remove_request") && (request.getPartAsString("remove_request", 32).length() > 0)) {
				String identifier = request.getPartAsString("identifier", MAX_IDENTIFIER_LENGTH);
				if(logMINOR) Logger.minor(this, "Removing "+identifier);
				try {
					fcp.removeGlobalRequest(identifier);
				} catch (MessageInvalidException e) {
					this.sendErrorPage(ctx, 200, "Failed to remove request", "Failed to remove " + identifier + ": " + e.getMessage());
				}
				writePermanentRedirect(ctx, "Done", "/queue/");
				return;
			} else if(request.isPartSet("restart_request") && (request.getPartAsString("restart_request", 32).length() > 0)) {
				String identifier = request.getPartAsString("identifier", MAX_IDENTIFIER_LENGTH);
				if(logMINOR) Logger.minor(this, "Restarting "+identifier);
				ClientRequest[] clientRequests = fcp.getGlobalRequests();
				for (int requestIndex = 0, requestCount = clientRequests.length; requestIndex < requestCount; requestIndex++) {
					ClientRequest clientRequest = clientRequests[requestIndex];
					if (clientRequest.getIdentifier().equals(identifier)) {
						if(!clientRequest.restart()) {
							sendErrorPage(ctx, 200, "Failed to restart request", "Failed to restart "+identifier);
						}
					}
				}
				writePermanentRedirect(ctx, "Done", "/queue/");
				return;
			} else if(request.isPartSet("remove_AllRequests") && (request.getPartAsString("remove_AllRequests", 32).length() > 0)) {
				
				ClientRequest[] reqs = fcp.getGlobalRequests();
				if(logMINOR) Logger.minor(this, "Request count: "+reqs.length);
				
				StringBuffer failedIdentifiers = new StringBuffer();
				
				for(int i=0; i<reqs.length ; i++){
					String identifier = reqs[i].getIdentifier();
					if(logMINOR) Logger.minor(this, "Removing "+identifier);
					try {
						fcp.removeGlobalRequest(identifier);
					} catch (MessageInvalidException e) {
						failedIdentifiers.append(identifier + ' ' + e.getMessage() + ';');
						Logger.error(this, "Failed to remove " + identifier + ':' + e.getMessage());
						continue;
					}
				}
				
				if(failedIdentifiers.length() > 0)
					this.sendErrorPage(ctx, 200, "Failed to remove request", "Failed to remove " + failedIdentifiers);
				else
					writePermanentRedirect(ctx, "Done", "/queue/");
				return;
			}else if(request.isPartSet("download")) {
				// Queue a download
				if(!request.isPartSet("key")) {
					writeError("No key specified to download", "You did not specify a key to download.", ctx);
					return;
				}
				String expectedMIMEType = null;
				if(request.isPartSet("type")) {
					expectedMIMEType = request.getPartAsString("type", MAX_TYPE_LENGTH);
				}
				FreenetURI fetchURI;
				try {
					fetchURI = new FreenetURI(request.getPartAsString("key", MAX_KEY_LENGTH));
				} catch (MalformedURLException e) {
					writeError("Invalid URI to download", "The URI is invalid and can not be downloaded.", ctx);
					return;
				}
				String persistence = request.getPartAsString("persistence", 32);
				String returnType = request.getPartAsString("return-type", 32);
				fcp.makePersistentGlobalRequest(fetchURI, expectedMIMEType, persistence, returnType);
				writePermanentRedirect(ctx, "Done", "/queue/");
				return;
			} else if (request.isPartSet("change_priority")) {
				String identifier = request.getPartAsString("identifier", MAX_IDENTIFIER_LENGTH);
				short newPriority = Short.parseShort(request.getPartAsString("priority", 32));
				ClientRequest[] clientRequests = fcp.getGlobalRequests();
loop:				for (int requestIndex = 0, requestCount = clientRequests.length; requestIndex < requestCount; requestIndex++) {
					ClientRequest clientRequest = clientRequests[requestIndex];
					if (clientRequest.getIdentifier().equals(identifier)) {
						clientRequest.setPriorityClass(newPriority);
						break loop;
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
				HTTPUploadedFile file = request.getUploadedFile("filename");
				if (file == null || file.getFilename().trim().length() == 0) {
					writeError("No file selected", "You did not select a file to upload.", ctx);
					return;
				}
				boolean compress = request.getPartAsString("compress", 128).length() > 0;
				String identifier = file.getFilename() + "-fred-" + System.currentTimeMillis();
				String fnam;
				if(insertURI.getKeyType().equals("CHK"))
					fnam = file.getFilename();
				else
					fnam = null;
				/* copy bucket data */
				Bucket copiedBucket = core.persistentEncryptedTempBucketFactory.makeBucket(file.getData().size());
				BucketTools.copy(file.getData(), copiedBucket);
				try {
					ClientPut clientPut = new ClientPut(fcp.getGlobalClient(), insertURI, identifier, Integer.MAX_VALUE, RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS, ClientRequest.PERSIST_FOREVER, null, false, !compress, -1, ClientPutMessage.UPLOAD_FROM_DIRECT, null, file.getContentType(), copiedBucket, null, fnam, false);
					clientPut.start();
					fcp.forceStorePersistentRequests();
				} catch (IdentifierCollisionException e) {
					e.printStackTrace();
				}
				writePermanentRedirect(ctx, "Done", "/queue/");
				return;
			} else if (request.isPartSet("insert-local-file")) {
				String filename = request.getPartAsString("filename", MAX_FILENAME_LENGTH);
				if(logMINOR) Logger.minor(this, "Inserting local file: "+filename);
				File file = new File(filename);
				String identifier = file.getName() + "-fred-" + System.currentTimeMillis();
				String contentType = DefaultMIMETypes.guessMIMEType(filename, false);
				try {
					ClientPut clientPut = new ClientPut(fcp.getGlobalClient(), new FreenetURI("CHK@"), identifier, Integer.MAX_VALUE, RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS, ClientRequest.PERSIST_FOREVER, null, false, false, -1, ClientPutMessage.UPLOAD_FROM_DISK, file, contentType, new FileBucket(file, true, false, false, false), null, file.getName(), false);
					if(logMINOR) Logger.minor(this, "Started global request to insert "+file+" to CHK@ as "+identifier);
					clientPut.start();
					fcp.forceStorePersistentRequests();
				} catch (IdentifierCollisionException e) {
					e.printStackTrace();
				}
				writePermanentRedirect(ctx, "Done", "/queue/");
				return;
			} else if (request.isPartSet("get")) {
				String identifier = request.getPartAsString("identifier", MAX_IDENTIFIER_LENGTH);
				ClientRequest[] clientRequests = fcp.getGlobalRequests();
loop:				for (int requestIndex = 0, requestCount = clientRequests.length; requestIndex < requestCount; requestIndex++) {
					ClientRequest clientRequest = clientRequests[requestIndex];
					if (clientRequest.getIdentifier().equals(identifier)) {
						if (clientRequest instanceof ClientGet) {
							ClientGet clientGet = (ClientGet) clientRequest;
							if (clientGet.hasSucceeded()) {
								Bucket dataBucket = clientGet.getBucket();
								if (dataBucket != null) {
									String forceDownload = request.getPartAsString("forceDownload", 32);
									if (forceDownload.length() > 0) {
										long forceDownloadTime = Long.parseLong(forceDownload);
										if ((System.currentTimeMillis() - forceDownloadTime) > 60 * 1000) {
											break loop;
										}
										MultiValueTable responseHeaders = new MultiValueTable();
										responseHeaders.put("Content-Disposition", "attachment; filename=\"" + clientGet.getURI().getPreferredFilename() + '"');
										writeReply(ctx, 200, "application/x-msdownload", "OK", responseHeaders, dataBucket);
										return;
									}
									HTMLNode pageNode = ctx.getPageMaker().getPageNode("Potentially Unsafe Content");
									HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
									HTMLNode alertNode = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-alert", "Potentially Unsafe Content"));
									HTMLNode alertContent = ctx.getPageMaker().getContentNode(alertNode);
									alertContent.addChild("#", "The file you want to download is currently not filtered by Freenet\u2019s content filter! That means that your anonymity can be compromised by opening the file!");
									HTMLNode optionListNode = alertContent.addChild("ul");
									HTMLNode optionForm = ctx.addFormChild(optionListNode, "/queue/", "queueDownloadNotFilteredConfirmForm");
									optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "identifier", identifier });
									optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "forceDownload", String.valueOf(System.currentTimeMillis()) });
									optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "get", "Download anyway" });
									optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "return", "Return to queue page" });
									writeReply(ctx, 200, "text/html; charset=utf-8", "OK", pageNode.generate());
									return;
								}
							}
							writeError("Download Not Completed", "The download has not yet completed.", ctx);
							return;
						}
					}
				}
				writeError("Download Not Found", "The download could not be found. Maybe it was already deleted?", ctx);
				return;
			}
		} finally {
			request.freeParts();
		}
		this.handleGet(uri, new HTTPRequestImpl(uri), ctx);
	}
	
	private void writeError(String header, String message, ToadletContext context) throws ToadletContextClosedException, IOException {
		PageMaker pageMaker = context.getPageMaker();
		HTMLNode pageNode = pageMaker.getPageNode(header);
		HTMLNode contentNode = pageMaker.getContentNode(pageNode);
		contentNode.addChild(core.alerts.createSummary());
		HTMLNode infobox = contentNode.addChild(pageMaker.getInfobox("infobox-error", header));
		HTMLNode infoboxContent = pageMaker.getContentNode(infobox);
		infoboxContent.addChild("#", message);
		infoboxContent.addChild("div").addChildren(new HTMLNode[] { new HTMLNode("#", "Return to "), new HTMLNode("a", "href", "/queue/", "queue page"), new HTMLNode("#", ".") });
		writeReply(context, 400, "text/html; charset=utf-8", "Error", pageNode.generate());
	}

	public void handleGet(URI uri, final HTTPRequest request, ToadletContext ctx) 
	throws ToadletContextClosedException, IOException, RedirectException {
		
		// We ensure that we have a FCP server running
		if(!fcp.enabled){
			this.writeReply(ctx, 400, "text/plain", "FCP server is missing", "You need to enable the FCP server to access this page");
			return;
		}
		
		final String requestPath = request.getPath().substring("/queue/".length());
		
		if (requestPath.length() > 0) {
			/* okay, there is something in the path, check it. */
			try {
				FreenetURI key = new FreenetURI(requestPath);
				
				/* locate request */
				ClientRequest[] clientRequests = fcp.getGlobalRequests();
				for (int requestIndex = 0, requestCount = clientRequests.length; requestIndex < requestCount; requestIndex++) {
					ClientRequest clientRequest = clientRequests[requestIndex];
					if (clientRequest.hasFinished() && (clientRequest instanceof ClientGet)) {
						ClientGet clientGet = (ClientGet) clientRequest;
						if (clientGet.getURI().equals(key)) {
							Bucket data = clientGet.getBucket();
							String mimeType = clientGet.getMIMEType();
							String requestedMimeType = request.getParam("type", null);
							String forceString = request.getParam("force");
							FProxyToadlet.handleDownload(ctx, data, ctx.getBucketFactory(), mimeType, requestedMimeType, forceString, request.isParameterSet("forcedownload"), "/queue/", key, "", "/queue/");
							return;
						}
					}
				}
			} catch (MalformedURLException mue1) {
			}
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
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Request count: "+reqs.length);
		
		if(reqs.length < 1){
			HTMLNode pageNode = pageMaker.getPageNode("Global queue of " + core.getMyName());
			HTMLNode contentNode = pageMaker.getContentNode(pageNode);
			/* add alert summary box */
			contentNode.addChild(core.alerts.createSummary());
			HTMLNode infobox = contentNode.addChild(pageMaker.getInfobox("infobox-information", "Global queue is empty"));
			HTMLNode infoboxContent = pageMaker.getContentNode(infobox);
			infoboxContent.addChild("#", "There is no task queued on the global queue at the moment.");
			contentNode.addChild(createInsertBox(pageMaker, ctx));
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
		
		Comparator jobComparator = new Comparator() {
			public int compare(Object first, Object second) {
				ClientRequest firstRequest = (ClientRequest) first;
				ClientRequest secondRequest = (ClientRequest) second;

				int result = 0;
				boolean isSet = true;
				
				if(request.isParameterSet("sortBy")){
					final String sortBy = request.getParam("sortBy"); 

					if(sortBy.equals("id")){
						result = firstRequest.getIdentifier().compareToIgnoreCase(secondRequest.getIdentifier());
					}else if(sortBy.equals("size")){
						result = (firstRequest.getTotalBlocks() - secondRequest.getTotalBlocks()) < 0 ? -1 : 1;
					}else if(sortBy.equals("progress")){
						result = firstRequest.getSuccessFraction() - secondRequest.getSuccessFraction() < 0 ? -1 : 1;
					}else
						isSet=false;
				}else
					isSet=false;
				
				if(!isSet){
					int priorityDifference =  firstRequest.getPriority() - secondRequest.getPriority(); 
					if (priorityDifference != 0) 
						result = (priorityDifference < 0 ? -1 : 1);
					else
						result = firstRequest.getIdentifier().compareTo(secondRequest.getIdentifier());
				}

				if(result == 0){
					return 0;
				}else if(request.isParameterSet("reversed")){
					isReversed = true;
					return result > 0 ? -1 : 1;
				}else{
					isReversed = false;
					return result < 0 ? -1 : 1;
				}
			}
		};
		
		Collections.sort(completedDownloadToDisk, jobComparator);
		Collections.sort(completedDownloadToTemp, jobComparator);
		Collections.sort(completedUpload, jobComparator);
		Collections.sort(completedDirUpload, jobComparator);
		Collections.sort(failedDownload, jobComparator);
		Collections.sort(failedUpload, jobComparator);
		Collections.sort(failedDirUpload, jobComparator);
		Collections.sort(uncompletedDownload, jobComparator);
		Collections.sort(uncompletedUpload, jobComparator);
		Collections.sort(uncompletedDirUpload, jobComparator);
		
		HTMLNode pageNode = pageMaker.getPageNode("(" + (uncompletedDirUpload.size() + uncompletedDownload.size()
				+ uncompletedUpload.size()) + '/' + (failedDirUpload.size() + failedDownload.size() + failedUpload.size()) + '/'
                + (completedDirUpload.size() + completedDownloadToDisk.size() + completedDownloadToTemp.size()
				+ completedUpload.size()) + ") Queued Requests of " + core.getMyName());
		HTMLNode contentNode = pageMaker.getContentNode(pageNode);

		/* add alert summary box */
		contentNode.addChild(core.alerts.createSummary());
		/* add file insert box */
		contentNode.addChild(createInsertBox(pageMaker, ctx));

		/* navigation bar */
		HTMLNode navigationBar = pageMaker.getInfobox("navbar", "Request Navigation");
		HTMLNode navigationContent = pageMaker.getContentNode(navigationBar).addChild("ul");
		boolean includeNavigationBar = false;
		if (!completedDownloadToTemp.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#completedDownloadToTemp", "Completed downloads to temp");
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

		if (reqs.length > 1 && SimpleToadletServer.isPanicButtonToBeShown) {
			contentNode.addChild(createPanicBox(pageMaker, ctx));
		}

		boolean advancedEnabled = core.isAdvancedDarknetEnabled();
		
		if (!completedDownloadToTemp.isEmpty()) {
			contentNode.addChild("a", "name", "completedDownloadToTemp");
			HTMLNode completedDownloadsTempInfobox = contentNode.addChild(pageMaker.getInfobox("completed_requests", "Completed: Downloads to temporary directory (" + completedDownloadToTemp.size() + ')'));
			HTMLNode completedDownloadsToTempContent = pageMaker.getContentNode(completedDownloadsTempInfobox);
			if (advancedEnabled) {
				completedDownloadsToTempContent.addChild(createRequestTable(pageMaker, ctx, completedDownloadToTemp, new int[] { LIST_IDENTIFIER, LIST_SIZE, LIST_MIME_TYPE, LIST_DOWNLOAD, LIST_PERSISTENCE, LIST_KEY }));
			} else {
				completedDownloadsToTempContent.addChild(createRequestTable(pageMaker, ctx, completedDownloadToTemp, new int[] { LIST_SIZE, LIST_MIME_TYPE, LIST_DOWNLOAD, LIST_PERSISTENCE, LIST_KEY }));
			}
		}
		
		if (!completedDownloadToDisk.isEmpty()) {
			contentNode.addChild("a", "name", "completedDownloadToDisk");
			HTMLNode completedToDiskInfobox = contentNode.addChild(pageMaker.getInfobox("completed_requests", "Completed: Downloads to download directory (" + completedDownloadToDisk.size() + ')'));
			HTMLNode completedToDiskInfoboxContent = pageMaker.getContentNode(completedToDiskInfobox);
			if (advancedEnabled) {
				completedToDiskInfoboxContent.addChild(createRequestTable(pageMaker, ctx, completedDownloadToDisk, new int[] { LIST_IDENTIFIER, LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_DOWNLOAD, LIST_PERSISTENCE, LIST_KEY }));
			} else {
				completedToDiskInfoboxContent.addChild(createRequestTable(pageMaker, ctx, completedDownloadToDisk, new int[] { LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_DOWNLOAD, LIST_PERSISTENCE, LIST_KEY }));
			}
		}

		if (!completedUpload.isEmpty()) {
			contentNode.addChild("a", "name", "completedUpload");
			HTMLNode completedUploadInfobox = contentNode.addChild(pageMaker.getInfobox("completed_requests", "Completed: Uploads (" + completedUpload.size() + ')'));
			HTMLNode completedUploadInfoboxContent = pageMaker.getContentNode(completedUploadInfobox);
			if (advancedEnabled) {
				completedUploadInfoboxContent.addChild(createRequestTable(pageMaker, ctx, completedUpload, new int[] { LIST_IDENTIFIER, LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_PERSISTENCE, LIST_KEY }));
			} else  {
				completedUploadInfoboxContent.addChild(createRequestTable(pageMaker, ctx, completedUpload, new int[] { LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_PERSISTENCE, LIST_KEY }));
			}
		}
		
		if (!completedDirUpload.isEmpty()) {
			contentNode.addChild("a", "name", "completedDirUpload");
			HTMLNode completedUploadDirInfobox = contentNode.addChild(pageMaker.getInfobox("completed_requests", "Completed: Directory Uploads (" + completedDirUpload.size() + ')'));
			HTMLNode completedUploadDirContent = pageMaker.getContentNode(completedUploadDirInfobox);
			if (advancedEnabled) {
				completedUploadDirContent.addChild(createRequestTable(pageMaker, ctx, completedDirUpload, new int[] { LIST_IDENTIFIER, LIST_FILES, LIST_TOTAL_SIZE, LIST_PERSISTENCE, LIST_KEY }));
			} else {
				completedUploadDirContent.addChild(createRequestTable(pageMaker, ctx, completedDirUpload, new int[] { LIST_FILES, LIST_TOTAL_SIZE, LIST_PERSISTENCE, LIST_KEY }));
			}
		}
				
		if (!failedDownload.isEmpty()) {
			contentNode.addChild("a", "name", "failedDownload");
			HTMLNode failedInfobox = contentNode.addChild(pageMaker.getInfobox("failed_requests", "Failed: Downloads (" + failedDownload.size() + ')'));
			HTMLNode failedContent = pageMaker.getContentNode(failedInfobox);
			if (advancedEnabled) {
				failedContent.addChild(createRequestTable(pageMaker, ctx, failedDownload, new int[] { LIST_IDENTIFIER, LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_PROGRESS, LIST_REASON, LIST_PERSISTENCE, LIST_KEY }));
			} else {
				failedContent.addChild(createRequestTable(pageMaker, ctx, failedDownload, new int[] { LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_PROGRESS, LIST_REASON, LIST_PERSISTENCE, LIST_KEY }));
			}
		}
		
		if (!failedUpload.isEmpty()) {
			contentNode.addChild("a", "name", "failedUpload");
			HTMLNode failedInfobox = contentNode.addChild(pageMaker.getInfobox("failed_requests", "Failed: Uploads (" + failedUpload.size() + ')'));
			HTMLNode failedContent = pageMaker.getContentNode(failedInfobox);
			if (advancedEnabled) {
				failedContent.addChild(createRequestTable(pageMaker, ctx, failedUpload, new int[] { LIST_IDENTIFIER, LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_PROGRESS, LIST_REASON, LIST_PERSISTENCE, LIST_KEY }));
			} else {
				failedContent.addChild(createRequestTable(pageMaker, ctx, failedUpload, new int[] { LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_PROGRESS, LIST_REASON, LIST_PERSISTENCE, LIST_KEY }));
			}
		}
		
		if (!failedDirUpload.isEmpty()) {
			contentNode.addChild("a", "name", "failedDirUpload");
			HTMLNode failedInfobox = contentNode.addChild(pageMaker.getInfobox("failed_requests", "Failed: Directory Uploads (" + failedDirUpload.size() + ')'));
			HTMLNode failedContent = pageMaker.getContentNode(failedInfobox);
			if (advancedEnabled) {
				failedContent.addChild(createRequestTable(pageMaker, ctx, failedDirUpload, new int[] { LIST_IDENTIFIER, LIST_FILES, LIST_TOTAL_SIZE, LIST_PROGRESS, LIST_REASON, LIST_PERSISTENCE, LIST_KEY }));
			} else {
				failedContent.addChild(createRequestTable(pageMaker, ctx, failedDirUpload, new int[] { LIST_FILES, LIST_TOTAL_SIZE, LIST_PROGRESS, LIST_REASON, LIST_PERSISTENCE, LIST_KEY }));
			}
		}
		
		if (!uncompletedDownload.isEmpty()) {
			contentNode.addChild("a", "name", "uncompletedDownload");
			HTMLNode uncompletedInfobox = contentNode.addChild(pageMaker.getInfobox("requests_in_progress", "In Progress: Downloads (" + uncompletedDownload.size() + ')'));
			HTMLNode uncompletedContent = pageMaker.getContentNode(uncompletedInfobox);
			if (advancedEnabled) {
				uncompletedContent.addChild(createRequestTable(pageMaker, ctx, uncompletedDownload, new int[] { LIST_IDENTIFIER, LIST_PRIORITY, LIST_SIZE, LIST_MIME_TYPE, LIST_PROGRESS, LIST_PERSISTENCE, LIST_FILENAME, LIST_KEY }));
			} else {
				uncompletedContent.addChild(createRequestTable(pageMaker, ctx, uncompletedDownload, new int[] { LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_PROGRESS, LIST_PERSISTENCE, LIST_KEY }));
			}
		}
		
		if (!uncompletedUpload.isEmpty()) {
			contentNode.addChild("a", "name", "uncompletedUpload");
			HTMLNode uncompletedInfobox = contentNode.addChild(pageMaker.getInfobox("requests_in_progress", "In Progress: Uploads (" + uncompletedUpload.size() + ')'));
			HTMLNode uncompletedContent = pageMaker.getContentNode(uncompletedInfobox);
			if (advancedEnabled) {
				uncompletedContent.addChild(createRequestTable(pageMaker, ctx, uncompletedUpload, new int[] { LIST_IDENTIFIER, LIST_PRIORITY, LIST_SIZE, LIST_MIME_TYPE, LIST_PROGRESS, LIST_PERSISTENCE, LIST_FILENAME, LIST_KEY }));
			} else {
				uncompletedContent.addChild(createRequestTable(pageMaker, ctx, uncompletedUpload, new int[] { LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_PROGRESS, LIST_PERSISTENCE, LIST_KEY }));
			}
		}
		
		if (!uncompletedDirUpload.isEmpty()) {
			contentNode.addChild("a", "name", "uncompletedDirUpload");
			HTMLNode uncompletedInfobox = contentNode.addChild(pageMaker.getInfobox("requests_in_progress", "In Progress: DirUploads (" + uncompletedDirUpload.size() + ')'));
			HTMLNode uncompletedContent = pageMaker.getContentNode(uncompletedInfobox);
			if (advancedEnabled) {
				uncompletedContent.addChild(createRequestTable(pageMaker, ctx, uncompletedDirUpload, new int[] { LIST_IDENTIFIER, LIST_FILES, LIST_PRIORITY, LIST_TOTAL_SIZE, LIST_PROGRESS, LIST_PERSISTENCE, LIST_KEY }));
			} else {
				uncompletedContent.addChild(createRequestTable(pageMaker, ctx, uncompletedDirUpload, new int[] { LIST_FILES, LIST_TOTAL_SIZE, LIST_PROGRESS, LIST_PERSISTENCE, LIST_KEY }));
			}
		}
		
		MultiValueTable pageHeaders = new MultiValueTable();
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
		if (!core.isAdvancedDarknetEnabled()) {
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
				progressBar.addChild("div", new String[] { "class", "title" }, new String[] { "progress_fraction_finalized", "This progress value is accurate" }, nf.format((int) ((fetched / (double) min) * 1000) / 10.0) + '%');
			} else {
				progressBar.addChild("div", new String[] { "class", "title" }, new String[] { "progress_fraction_not_finalized", "This progress value is likely to change as the file download process has not been finalized" }, nf.format((int) ((fetched / (double) min) * 1000) / 10.0)+ '%');
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

	private HTMLNode createPriorityCell(PageMaker pageMaker, String identifier, short priorityClass, ToadletContext ctx) {
		HTMLNode priorityCell = new HTMLNode("td", "class", "request-priority nowrap");
		HTMLNode priorityForm = ctx.addFormChild(priorityCell, "/queue/", "queueChangePriorityCell");
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

	private HTMLNode createDeleteCell(PageMaker pageMaker, String identifier, ClientRequest clientRequest, ToadletContext ctx) {
		HTMLNode deleteNode = new HTMLNode("td", "class", "request-delete");
		HTMLNode deleteForm = ctx.addFormChild(deleteNode, "/queue/", "queueDeleteForm");
		deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "identifier", identifier });
		deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "remove_request", "Delete" });
		
		// If it's failed, offer to restart it
		
		if(clientRequest.hasFinished() && !clientRequest.hasSucceeded() && clientRequest.canRestart()) {
			HTMLNode retryForm = ctx.addFormChild(deleteNode, "/queue/", "queueRestartForm");
			retryForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "identifier", identifier });
			retryForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "restart_request", "Retry" });
		}
		
		return deleteNode;
	}
	
	private HTMLNode createPanicBox(PageMaker pageMaker, ToadletContext ctx) {
		HTMLNode panicBox = pageMaker.getInfobox("infobox-alert", "Panic Button");
		HTMLNode panicForm = ctx.addFormChild(pageMaker.getContentNode(panicBox), "/queue/", "queuePanicForm");
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

	private HTMLNode createDownloadCell(PageMaker pageMaker, ClientGet p) {
		HTMLNode downloadCell = new HTMLNode("td", "class", "request-download");
		downloadCell.addChild("a", "href", p.getURI().toString(), "Download");
		return downloadCell;
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
			keyCell.addChild("span", "class", "key_is").addChild("a", "href", '/' + uri.toString(), uri.toShortString());
		} else {
			keyCell.addChild("span", "class", "key_unknown", "unknown");
		}
		return keyCell;
	}
	
	private HTMLNode createInsertBox(PageMaker pageMaker, ToadletContext ctx) {
		/* the insert file box */
		HTMLNode insertBox = pageMaker.getInfobox("Insert File");
		HTMLNode insertContent = pageMaker.getContentNode(insertBox);
		HTMLNode insertForm = ctx.addFormChild(insertContent, "/queue/", "queueInsertForm");
		insertForm.addChild("#", "Insert as: ");
		insertForm.addChild("input", new String[] { "type", "name", "value", "checked" }, new String[] { "radio", "keytype", "chk", "checked" });
		insertForm.addChild("#", " CHK \u00a0 ");
		insertForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "radio", "keytype", "ksk" });
		insertForm.addChild("#", " KSK \u00a0 ");
		insertForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "text", "key", "KSK@" });
		insertForm.addChild("#", " \u00a0 File: ");
		insertForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "file", "filename", "" });
		insertForm.addChild("#", " \u00a0 ");
		insertForm.addChild("input", new String[] { "type", "name", "checked" }, new String[] { "checkbox", "compress", "checked" });
		insertForm.addChild("#", " Compress \u00a0 ");
		insertForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "insert", "Insert file" });
		insertForm.addChild("#", " \u00a0 ");
		insertForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "insert-local", "Browse..." });
		insertForm.addChild("#", " \u00a0 ");
		insertForm.addChild("input", new String[] { "type", "name" }, new String[] { "reset", "Reset form" });
		return insertBox;
	}
	
	private HTMLNode createRequestTable(PageMaker pageMaker, ToadletContext ctx, List requests, int[] columns) {
		HTMLNode table = new HTMLNode("table", "class", "requests");
		HTMLNode headerRow = table.addChild("tr", "class", "table-header");
		headerRow.addChild("th");
		for (int columnIndex = 0, columnCount = columns.length; columnIndex < columnCount; columnIndex++) {
			int column = columns[columnIndex];
			if (column == LIST_IDENTIFIER) {
				headerRow.addChild("th").addChild("a", "href", (isReversed ? "?sortBy=id" : "?sortBy=id&reversed")).addChild("#", "Identifier");
			} else if (column == LIST_SIZE) {
				headerRow.addChild("th").addChild("a", "href", (isReversed ? "?sortBy=size" : "?sortBy=size&reversed")).addChild("#", "Size");
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
				headerRow.addChild("th").addChild("a", "href", (isReversed ? "?sortBy=priority" : "?sortBy=priority&reversed")).addChild("#", "Priority");
			} else if (column == LIST_FILES) {
				headerRow.addChild("th", "Files");
			} else if (column == LIST_TOTAL_SIZE) {
				headerRow.addChild("th", "Total Size");
			} else if (column == LIST_PROGRESS) {
				headerRow.addChild("th").addChild("a", "href", (isReversed ? "?sortBy=progress" : "?sortBy=progress&reversed")).addChild("#", "Progress");
			} else if (column == LIST_REASON) {
				headerRow.addChild("th", "Reason");
			}
		}
		for (Iterator requestItems = requests.iterator(); requestItems.hasNext(); ) {
			ClientRequest clientRequest = (ClientRequest) requestItems.next();
			HTMLNode requestRow = table.addChild("tr", "class", "priority" + clientRequest.getPriority());
			
			requestRow.addChild(createDeleteCell(pageMaker, clientRequest.getIdentifier(), clientRequest, ctx));
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
					requestRow.addChild(createDownloadCell(pageMaker, (ClientGet) clientRequest));
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
					} else if (clientRequest instanceof ClientPut) {
						requestRow.addChild(createKeyCell(((ClientPut) clientRequest).getFinalURI()));
					}else {
						requestRow.addChild(createKeyCell(((ClientPutDir) clientRequest).getFinalURI()));
					}
				} else if (column == LIST_FILENAME) {
					if (clientRequest instanceof ClientGet) {
						requestRow.addChild(createFilenameCell(((ClientGet) clientRequest).getDestFilename()));
					} else if (clientRequest instanceof ClientPut) {
						requestRow.addChild(createFilenameCell(((ClientPut) clientRequest).getOrigFilename()));
					}
				} else if (column == LIST_PRIORITY) {
					requestRow.addChild(createPriorityCell(pageMaker, clientRequest.getIdentifier(), clientRequest.getPriority(), ctx));
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
