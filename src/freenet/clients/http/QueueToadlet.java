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
import freenet.support.HTMLDecoder;
import freenet.support.HTMLEncoder;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.SizeUtil;
import freenet.support.URLEncoder;
import freenet.support.io.Bucket;

public class QueueToadlet extends Toadlet {

	private static final String[] priorityClasses = new String[] { "emergency", "very high", "high", "medium", "low", "very low", "will never finish" };

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
			String identifier = HTMLDecoder.decode(request.getParam("identifier"));
			Logger.minor(this, "Removing "+identifier);
			try {
				fcp.removeGlobalRequest(identifier);
			} catch (MessageInvalidException e) {
				this.sendErrorPage(ctx, 200, "Failed to remove request", "Failed to remove "+HTMLEncoder.encode(identifier)+" : "+HTMLEncoder.encode(e.getMessage()));
			}
			writePermanentRedirect(ctx, "Done", "/queue/");
			return;
		}else if(request.isParameterSet("remove_AllRequests") && (request.getParam("remove_AllRequests").length() > 0)) {
			
			ClientRequest[] reqs = fcp.getGlobalRequests();
			Logger.minor(this, "Request count: "+reqs.length);
			
			for(int i=0; i<reqs.length ; i++){
				String identifier = HTMLDecoder.decode(reqs[i].getIdentifier());
				Logger.minor(this, "Removing "+identifier);
				try {
					fcp.removeGlobalRequest(identifier);
				} catch (MessageInvalidException e) {
					this.sendErrorPage(ctx, 200, "Failed to remove request", "Failed to remove "+HTMLEncoder.encode(identifier)+" : "+HTMLEncoder.encode(e.getMessage()));
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
				fetchURI = new FreenetURI(HTMLDecoder.decode(request.getParam("key")));
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
			String identifier = HTMLDecoder.decode(request.getParam("identifier"));
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
			boolean dontCompress = request.getPartAsString("dontCompress", 128).length() > 0;
			HTTPRequest.File file = request.getUploadedFile("filename");
			String identifier = file.getFilename() + "-fred-" + System.currentTimeMillis();
			try {
				ClientPut clientPut = new ClientPut(fcp.getGlobalClient(), insertURI, identifier, Integer.MAX_VALUE, RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS, ClientRequest.PERSIST_FOREVER, null, false, dontCompress, -1, ClientPutMessage.UPLOAD_FROM_DIRECT, new File(file.getFilename()), file.getContentType(), file.getData(), null);
				clientPut.start();
				fcp.forceStorePersistentRequests();
			} catch (IdentifierCollisionException e) {
				e.printStackTrace();
			}
			writePermanentRedirect(ctx, "Done", "/queue/");
			return;
		}
		this.handleGet(uri, ctx);
	}
	
	private void writeError(String string, String string2, ToadletContext context) throws ToadletContextClosedException, IOException {
		StringBuffer buffer = new StringBuffer();
		context.getPageMaker().makeHead(buffer, "Error processing request");
		writeBigHeading(string, buffer, null);
		buffer.append(string2);
		writeBigEnding(buffer);
		context.getPageMaker().makeTail(buffer);
		writeReply(context, 400, "text/html; charset=utf-8", "Error", buffer.toString());
	}

	public void handleGet(URI uri, ToadletContext ctx) 
	throws ToadletContextClosedException, IOException, RedirectException {
		
		// We ensure that we have a FCP server running
		if(!fcp.enabled){
			this.writeReply(ctx, 400, "text/plain", "FCP server is missing", "You need to enable the FCP server to access this page");
			return;
		}
		
		StringBuffer buf = new StringBuffer(2048);
		
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
			ctx.getPageMaker().makeHead(buf, "Global Queue of "+node.getMyName());
			buf.append("<div class=\"infobox infobox-information\">\n");
			buf.append("<div class=\"infobox-header\">\n");
			buf.append("Global queue is empty!\n");
			buf.append("</div>\n");
			buf.append("<div class=\"infobox-content\">\n");
			buf.append("There is no task queued on the global queue at the moment.\n");
			buf.append("</form>\n");
			buf.append("</div>\n");
			buf.append("</div>\n");
			writeInsertBox(buf);
			ctx.getPageMaker().makeTail(buf);
			writeReply(ctx, 200, "text/html", "OK", buf.toString());
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
		
		ctx.getPageMaker().makeHead(buf, "("+(uncompletedDirUpload.size()+uncompletedDownload.size()+uncompletedUpload.size())+
				"/"+(failedDirUpload.size()+failedDownload.size()+failedUpload.size())+
				"/"+(completedDirUpload.size()+completedDownloadToDisk.size()+completedDownloadToTemp.size()+completedUpload.size())+
				") Queued Requests");
		
		node.alerts.toSummaryHtml(buf);
		
		writeInsertBox(buf);
		
		writeBigHeading("Legend", buf, "legend");
		buf.append("<table class=\"queue\">\n");
		buf.append("<tr>");
		for(int i=0; i<7; i++){
			buf.append("<td class=\"priority"+i+"\">priority "+i+"</td>");
		}
		buf.append("</tr>\n");
		writeTableEnd(buf);
		if(reqs.length > 1)
			writeDeleteAll(buf);
		writeBigEnding(buf);
		
		if(!(completedDownloadToTemp.isEmpty() && completedDownloadToDisk.isEmpty() &&
				completedUpload.isEmpty() && completedDirUpload.isEmpty())) {
			writeBigHeading("Completed requests (" + (completedDownloadToTemp.size() + completedDownloadToDisk.size() + completedUpload.size() + completedDirUpload.size()) + ")", buf, "completed_requests");
			
			if(!completedDownloadToTemp.isEmpty()) {
				if (node.getToadletContainer().isAdvancedDarknetEnabled())
					writeTableHead("Completed downloads to temporary space", new String[] { "", "Identifier", "Size", "MIME-Type", "Download", "Persistence", "Key" }, buf );
				else
					writeTableHead("Completed downloads to temporary space", new String[] { "", "Size", "MIME-Type", "Download", "Persistence", "Key" }, buf );
				for(Iterator i = completedDownloadToTemp.iterator();i.hasNext();) {
					ClientGet p = (ClientGet) i.next();
					writeRowStart(buf,p);
					writeDeleteCell(p, buf);
					if (node.getToadletContainer().isAdvancedDarknetEnabled())
						writeIdentifierCell(p, p.getURI(), buf);
					writeSizeCell(p.getDataSize(), buf);
					writeTypeCell(p.getMIMEType(), buf);
					writeDownloadCell(p, buf);
					writePersistenceCell(p, buf);
					writeKeyCell(p.getURI(), buf);
					writeRowEnd(buf);
				}
				writeTableEnd(buf);
			}
			
			if(!completedDownloadToDisk.isEmpty()) {
				if (node.getToadletContainer().isAdvancedDarknetEnabled())
					writeTableHead("Completed downloads to disk", new String[] { "", "Identifier", "Filename", "Size", "MIME-Type", "Download", "Persistence", "Key" }, buf);
				else
					writeTableHead("Completed downloads to disk", new String[] { "", "Filename", "Size", "MIME-Type", "Download", "Persistence", "Key" }, buf);
				for(Iterator i=completedDownloadToDisk.iterator();i.hasNext();) {
					ClientGet p = (ClientGet) i.next();
					writeRowStart(buf,p);
					writeDeleteCell(p, buf);
					if (node.getToadletContainer().isAdvancedDarknetEnabled())
						writeIdentifierCell(p, p.getURI(), buf);
					writeFilenameCell(p.getDestFilename(), buf);
					writeSizeCell(p.getDataSize(), buf);
					writeTypeCell(p.getMIMEType(), buf);
					writeDownloadCell(p, buf);
					writePersistenceCell(p, buf);
					writeKeyCell(p.getURI(), buf);
					writeRowEnd(buf);
				}
				writeTableEnd(buf);
			}

			if(!completedUpload.isEmpty()) {
				if (node.getToadletContainer().isAdvancedDarknetEnabled())
					writeTableHead("Completed uploads", new String[] { "", "Identifier", "Filename", "Size", "MIME-Type", "Persistence", "Key" }, buf);
				else
					writeTableHead("Completed uploads", new String[] { "", "Filename", "Size", "MIME-Type", "Persistence", "Key" }, buf);
				for(Iterator i=completedUpload.iterator();i.hasNext();) {
					ClientPut p = (ClientPut) i.next();
					writeRowStart(buf,p);
					writeDeleteCell(p, buf);
					if (node.getToadletContainer().isAdvancedDarknetEnabled())
						writeIdentifierCell(p, p.getFinalURI(), buf);
					if(p.isDirect())
						writeDirectCell(buf);
					else
						writeFilenameCell(p.getOrigFilename(), buf);
					writeSizeCell(p.getDataSize(), buf);
					writeTypeCell(p.getMIMEType(), buf);
					writePersistenceCell(p, buf);
					writeKeyCell(p.getFinalURI(), buf);
					writeRowEnd(buf);
				}
				writeTableEnd(buf);
			}
			
			if(!completedDirUpload.isEmpty()) {
				// FIXME include filename??
				if (node.getToadletContainer().isAdvancedDarknetEnabled())
					writeTableHead("Completed directory uploads", new String[] { "", "Identifier", "Files", "Total Size", "Persistence", "Key" }, buf);
				else
					writeTableHead("Completed directory uploads", new String[] { "", "Files", "Total Size", "Persistence", "Key" }, buf);
				for(Iterator i=completedDirUpload.iterator();i.hasNext();) {
					ClientPutDir p = (ClientPutDir) i.next();
					writeRowStart(buf,p);
					writeDeleteCell(p, buf);
					if (node.getToadletContainer().isAdvancedDarknetEnabled())
						writeIdentifierCell(p, p.getFinalURI(), buf);
					writeNumberCell(p.getNumberOfFiles(), buf);
					writeSizeCell(p.getTotalDataSize(), buf);
					writePersistenceCell(p, buf);
					writeKeyCell(p.getFinalURI(), buf);
					writeRowEnd(buf);
				}
				writeTableEnd(buf);
			}
			writeBigEnding(buf);
		}
		
		if(!(failedDownload.isEmpty() && failedUpload.isEmpty() && failedDirUpload.isEmpty())) {
			writeBigHeading("Failed requests (" + (failedDownload.size() + failedUpload.size() + failedDirUpload.size()) + ")", buf, "failed_requests");
			if(!failedDownload.isEmpty()) {
				if (node.getToadletContainer().isAdvancedDarknetEnabled())
					writeTableHead("Failed downloads", new String[] { "", "Identifier", "Filename", "Size", "MIME-Type", "Progress", "Reason", "Persistence", "Key" }, buf);
				else
					writeTableHead("Failed downloads", new String[] { "", "Filename", "Size", "MIME-Type", "Progress", "Reason", "Persistence", "Key" }, buf);
				for(Iterator i=failedDownload.iterator();i.hasNext();) {
					ClientGet p = (ClientGet) i.next();
					writeRowStart(buf,p);
					writeDeleteCell(p, buf);
					if (node.getToadletContainer().isAdvancedDarknetEnabled())
						writeIdentifierCell(p, p.getURI(), buf);
					if(p.isDirect())
						writeDirectCell(buf);
					else
						writeFilenameCell(p.getDestFilename(), buf);
					writeSizeCell(p.getDataSize(), buf);
					writeTypeCell(p.getMIMEType(), buf);
					writeProgressFractionCell(p, buf);
					writeFailureReasonCell(p, buf);
					writePersistenceCell(p, buf);
					writeKeyCell(p.getURI(), buf);
					writeRowEnd(buf);
				}
				writeTableEnd(buf);
			}
			
			if(!failedUpload.isEmpty()) {
				if (node.getToadletContainer().isAdvancedDarknetEnabled())
					writeTableHead("Failed uploads", new String[] { "", "Identifier", "Filename", "Size", "MIME-Type", "Progress", "Reason", "Persistence", "Key" }, buf);
				else
					writeTableHead("Failed uploads", new String[] { "", "Filename", "Size", "MIME-Type", "Progress", "Reason", "Persistence", "Key" }, buf);
				for(Iterator i=failedUpload.iterator();i.hasNext();) {
					ClientPut p = (ClientPut) i.next();
					writeRowStart(buf,p);
					writeDeleteCell(p, buf);
					if (node.getToadletContainer().isAdvancedDarknetEnabled())
						writeIdentifierCell(p, p.getFinalURI(), buf);
					if(p.isDirect())
						writeDirectCell(buf);
					else
						writeFilenameCell(p.getOrigFilename(), buf);
					writeSizeCell(p.getDataSize(), buf);
					writeTypeCell(p.getMIMEType(), buf);
					writeProgressFractionCell(p, buf);
					writeFailureReasonCell(p, buf);
					writePersistenceCell(p, buf);
					writeKeyCell(p.getFinalURI(), buf);
					writeRowEnd(buf);
				}
				writeTableEnd(buf);
			}
			
			if(!failedDirUpload.isEmpty()) {
				if (node.getToadletContainer().isAdvancedDarknetEnabled())
					writeTableHead("Failed directory uploads", new String[] { "", "Identifier", "Files", "Total Size", "Progress", "Reason", "Persistence", "Key" }, buf);
				else
					writeTableHead("Failed directory uploads", new String[] { "", "Files", "Total Size", "Progress", "Reason", "Persistence", "Key" }, buf);
				for(Iterator i=failedDirUpload.iterator();i.hasNext();) {
					ClientPutDir p = (ClientPutDir) i.next();
					writeRowStart(buf,p);
					writeDeleteCell(p, buf);
					if (node.getToadletContainer().isAdvancedDarknetEnabled())
						writeIdentifierCell(p, p.getFinalURI(), buf);
					writeNumberCell(p.getNumberOfFiles(), buf);
					writeSizeCell(p.getTotalDataSize(), buf);
					writeProgressFractionCell(p, buf);
					writeFailureReasonCell(p, buf);
					writePersistenceCell(p, buf);
					writeKeyCell(p.getFinalURI(), buf);
					writeRowEnd(buf);
				}
				writeTableEnd(buf);
			}
			writeBigEnding(buf);
		}
		
		if(!(uncompletedDownload.isEmpty() && uncompletedUpload.isEmpty() && 
				uncompletedDirUpload.isEmpty())) {
			writeBigHeading("Requests in progress (" + (uncompletedDownload.size() + uncompletedUpload.size() + uncompletedDirUpload.size()) + ")", buf, "requests_in_progress");
			if(!uncompletedDownload.isEmpty()) {
				if (node.getToadletContainer().isAdvancedDarknetEnabled())
					writeTableHead("Downloads in progress", new String[] {
							"", "Identifier", "Filename", "Priority", "Size", "MIME-Type", "Progress", "Persistence", "Key" 
					}, buf);
				else
					writeTableHead("Downloads in progress", new String[] {
							"", "Filename", "Size", "MIME-Type", "Progress", "Persistence", "Key" 
					}, buf);
				for(Iterator i = uncompletedDownload.iterator();i.hasNext();) {
					ClientGet p = (ClientGet) i.next();
					writeRowStart(buf,p);
					writeDeleteCell(p, buf);
					if (node.getToadletContainer().isAdvancedDarknetEnabled())
						writeIdentifierCell(p, p.getURI(), buf);
					if(p.isDirect())
						writeDirectCell(buf);
					else
						writeFilenameCell(p.getDestFilename(), buf);
					if (node.getToadletContainer().isAdvancedDarknetEnabled()) 
						writePriorityCell(p.getIdentifier(), p.getPriority(), buf);
					writeSizeCell(p.getDataSize(), buf);
					writeTypeCell(p.getMIMEType(), buf);
					writeProgressFractionCell(p, buf);
					writePersistenceCell(p, buf);
					writeKeyCell(p.getURI(), buf);
					writeRowEnd(buf);
				}
				writeTableEnd(buf);
			}

			if(!uncompletedUpload.isEmpty()) {
				if (node.getToadletContainer().isAdvancedDarknetEnabled())
					writeTableHead("Uploads in progress", new String[] { 
							"", "Identifier", "Filename", "Priority",  "Size", "MIME-Type", "Progress", "Persistence", "Key" 
					}, buf);
				else
					writeTableHead("Uploads in progress", new String[] {
							"", "Filename", "Size", "MIME-Type", "Progress", "Persistence", "Key" 
					}, buf);
				for(Iterator i = uncompletedUpload.iterator();i.hasNext();) {
					ClientPut p = (ClientPut) i.next();
					writeRowStart(buf,p);
					writeDeleteCell(p, buf);
					if (node.getToadletContainer().isAdvancedDarknetEnabled())
						writeIdentifierCell(p, p.getFinalURI(), buf);
					if(p.isDirect())
						writeDirectCell(buf);
					else
						writeFilenameCell(p.getOrigFilename(), buf);
					if (node.getToadletContainer().isAdvancedDarknetEnabled())
						writePriorityCell(p.getIdentifier(), p.getPriority(), buf);
					writeSizeCell(p.getDataSize(), buf);
					writeTypeCell(p.getMIMEType(), buf);
					writeProgressFractionCell(p, buf);
					writePersistenceCell(p, buf);
					writeKeyCell(p.getFinalURI(), buf);
					writeRowEnd(buf);
				}
				writeTableEnd(buf);
			}

			if(!uncompletedDirUpload.isEmpty()) {
				if (node.getToadletContainer().isAdvancedDarknetEnabled())
					writeTableHead("Directory uploads in progress", new String[] {
							"", "Identifier", "Priority", "Files", "Total Size", "Progress", "Persistence", "Key"
					}, buf);
				else
					writeTableHead("Directory uploads in progress", new String[] {
							"", "Files", "Total Size", "Progress", "Persistence", "Key"
					}, buf);
				for(Iterator i=uncompletedDirUpload.iterator();i.hasNext();) {
					ClientPutDir p = (ClientPutDir) i.next();
					writeRowStart(buf,p);
					writeDeleteCell(p, buf);
					if (node.getToadletContainer().isAdvancedDarknetEnabled()){
						writeIdentifierCell(p, p.getFinalURI(), buf);
						writePriorityCell(p.getIdentifier(), p.getPriority(), buf);
					}
					writeNumberCell(p.getNumberOfFiles(), buf);
					writeSizeCell(p.getTotalDataSize(), buf);
					writeProgressFractionCell(p, buf);
					writePersistenceCell(p, buf);
					writeKeyCell(p.getFinalURI(), buf);
					writeRowEnd(buf);
				}
				writeTableEnd(buf);
			}
			writeBigEnding(buf);
		}
		
		ctx.getPageMaker().makeTail(buf);

		this.writeReply(ctx, 200, "text/html", "OK", buf.toString());
	}

	
	private void writeFailureReasonCell(ClientRequest p, StringBuffer buf) {
		buf.append("<td>");
		String s = p.getFailureReason();
		if(s == null)
			buf.append("<span class=\"failure_reason_unknown\">unknown</span>");
		else
			buf.append("<span class=\"failure_reason_is\">" + s + "</span>");
		buf.append("</td>\n");
	}

	private void writeProgressFractionCell(ClientRequest p, StringBuffer buf) {
		buf.append("<td>");
		
		if(!p.isStarted()) {
			buf.append("STARTING</td>");
			return;
		}
		
		//double frac = p.getSuccessFraction();
		double total;
		if(node.getToadletContainer().isAdvancedDarknetEnabled())
			total = p.getTotalBlocks();
		else
			total = p.getMinBlocks();
		// All are fractions
		double fetched = p.getFetchedBlocks()/total;
		double failed = p.getFailedBlocks()/total;
		double failed2 = p.getFatalyFailedBlocks()/total;
		double min = p.getMinBlocks()/total;

		if (Double.isNaN(fetched)) fetched = 0.0;
		if (Double.isNaN(failed)) failed = 0.0;
		if (Double.isNaN(failed2)) failed2 = 0.0;
		if (Double.isNaN(min)) min = 0.0;
		if(min == 0.0) min = 1.0;
		
		boolean b = p.isTotalFinalized();
		if(fetched < 0) {
			buf.append("<span class=\"progress_fraction_unknown\">unknown</span>");
		} else {
			NumberFormat nf = NumberFormat.getInstance();
			nf.setMaximumFractionDigits(0);
			buf.append("<div class=\"progressbar\">"+
					"<div class=\"progressbar-done\" style=\"width: "+nf.format(fetched*100)+"px\"></div>");
			if(node.getToadletContainer().isAdvancedDarknetEnabled())
			{
				if(failed > 0)
					buf.append("<div class=\"progressbar-failed\" style=\"width: "+nf.format(failed*100)+"px\"></div>");
				if(failed2 > 0)
					buf.append("<div class=\"progressbar-failed2\" style=\"width: "+nf.format(failed2*100)+"px\"></div>");
				if(fetched < min)
					buf.append("<div class=\"progressbar-min\" style=\"width: "+nf.format((min-fetched)*100)+"px\"></div>");
			}
			buf.append("</div>");
			
			nf.setMaximumFractionDigits(1);
			if(b)
				buf.append("<span class=\"progress_fraction_finalized\">");
			else
				buf.append("<span class=\"progress_fraction_not_finalized\">");
			buf.append(nf.format(fetched*100));
			buf.append("%</span>");
		}
		buf.append("</td>\n");
	}

	private void writeNumberCell(int numberOfFiles, StringBuffer buf) {
		buf.append("<td>");
		buf.append("<span class=\"number_of_files\">" + numberOfFiles + "</span>");
		buf.append("</td>\n");
	}

	private void writeDirectCell(StringBuffer buf) {
		buf.append("<td>");
		buf.append("<span class=\"filename_direct\">direct</span>");
		buf.append("</td>\n");
	}

	private void writeFilenameCell(File destFilename, StringBuffer buf) {
		buf.append("<td>");
		if(destFilename == null)
			buf.append("<span class=\"filename_none\">none</span>");
		else
			buf.append("<span class=\"filename_is\">" + HTMLEncoder.encode(destFilename.toString()) + "</span>");
		buf.append("</td>\n");
	}

	private void writePriorityCell(String identifier, short priorityClass, StringBuffer buf) {
		buf.append("<td class=\"nowrap\">");
		buf.append("<form action=\"/queue/\" method=\"post\">");
		buf.append("<input type=\"hidden\" name=\"formPassword\" value=\"").append(node.formPassword).append("\" />");
		buf.append("<input type=\"hidden\" name=\"identifier\" value=\"").append(HTMLEncoder.encode(identifier)).append("\" />");
		buf.append("<select name=\"priority\">");
		for (int p = 0; p < RequestStarter.NUMBER_OF_PRIORITY_CLASSES; p++) {
			buf.append("<option value=\"").append(p);
			if (p == priorityClass) {
				buf.append("\" selected=\"selected");
			}
			buf.append("\">");
			buf.append(priorityClasses[p]);
			buf.append("</option>");
		}
		buf.append("</select>");
		buf.append("<input type=\"submit\" name=\"change_priority\" value=\"Change\" />");
		buf.append("</form>");
		buf.append("</td>");
	}

	private void writeDeleteCell(ClientRequest p, StringBuffer buf) {
		buf.append("<td>");
		buf.append("<form action=\"/queue/\" method=\"post\">");
		buf.append("<input type=\"hidden\" name=\"formPassword\" value=\""+node.formPassword+"\" />");
		buf.append("<input type=\"hidden\" name=\"identifier\" value=\"");
		buf.append(HTMLEncoder.encode(p.getIdentifier()));
		buf.append("\" /><input type=\"submit\" name=\"remove_request\" value=\"Delete\" />");
		buf.append("</form>\n");
		buf.append("</td>\n");
	}
	
	private void writeDeleteAll(StringBuffer buf) {
		buf.append("<td>");
		buf.append("<form action=\"/queue/\" method=\"post\">");
		buf.append("<input type=\"hidden\" name=\"formPassword\" value=\""+node.formPassword+"\" />");
		buf.append("<input type=\"submit\" name=\"remove_AllRequests\" value=\"Delete Everything\" />");
		buf.append("</form>\n");
		buf.append("</td>\n");
	}
	
	private void writeIdentifierCell(ClientRequest p, FreenetURI uri, StringBuffer buf) {
		buf.append("<td>");
		if(uri != null) {
			buf.append("<span class=\"identifier_with_uri\"><a href=\"/");
			buf.append(HTMLEncoder.encode(uri.toString(false)));
			buf.append("\">");
			buf.append(HTMLEncoder.encode(p.getIdentifier()));
			buf.append("</a></span>");
		}
		else {
			buf.append("<span class=\"identifier_without_uri\">");
			buf.append(HTMLEncoder.encode(p.getIdentifier()));
			buf.append("</span>");
		}
		buf.append("</td>\n");
	}

	private void writePersistenceCell(ClientRequest p, StringBuffer buf) {
		buf.append("<td>");
		if(!p.isPersistent())
			buf.append("<span class=\"persistence_none\">none</span>");
		else if(!p.isPersistentForever())
			buf.append("<span class=\"persistence_reboot\">reboot</span>");
		else
			buf.append("<span class=\"persistence_forever\">forever</span>");
		buf.append("</td>\n");
	}

	private void writeDownloadCell(ClientGet p, StringBuffer buf) {
		buf.append("<td>");
		buf.append("FIXME");
		buf.append("</td>");
	}

	private void writeTypeCell(String type, StringBuffer buf) {
		buf.append("<td>");
		if(type != null)
			buf.append("<span class=\"mimetype_is\">" + type + "</span>");
		else
			buf.append("<span class=\"mimetype_unknown\">unknown</span>");
		buf.append("</td>\n");
	}

	private void writeSizeCell(long dataSize, StringBuffer buf) {
		buf.append("<td>");
		if(dataSize >= 0)
			buf.append("<span class=\"filesize_is\">" + SizeUtil.formatSize(dataSize) + "</span>");
		else
			buf.append("<span class=\"filesize_unknown\">unknown</span>");
		buf.append("</td>\n");
	}

	private void writeKeyCell(FreenetURI uri, StringBuffer buf) {
		buf.append("<td>");
		if(uri != null) {
			buf.append("<span class=\"key_is\"><a href=\"/");
			String u = uri.toString(false);
			buf.append(URLEncoder.encode(u));
			buf.append("\">");
			u = uri.toShortString();
			buf.append(HTMLEncoder.encode(u));
			buf.append("</a></span>");
		} else {
			buf.append("<span class=\"key_unknown\">unknown</span>");
		}
		buf.append("</td>\n");
	}

	private void writeRowStart(StringBuffer buf, ClientRequest p) {
		buf.append("<tr class=\"priority"+p.getPriority()+"\">");
	}

	private void writeRowEnd(StringBuffer buf) {
		buf.append("</tr>\n");
	}

	private void writeTableHead(String tabletitle, String[] strings, StringBuffer buf) {
		buf.append("<div class=\"queue_tabletitle\">" + tabletitle + "</div>");
		buf.append("<table class=\"queue\">\n");
		buf.append("<tr>");
		for(int i=0;i<strings.length;i++) {
			buf.append("<th>");
			buf.append(strings[i]);
			buf.append("</th>");
		}
		buf.append("</tr>\n");
	}

	private void writeTableEnd(StringBuffer buf) {
		buf.append("</table>");
	}

	private void writeBigHeading(String header, StringBuffer buf, String id) {
		buf.append("<div class=\"infobox infobox-normal\"" + ((id != null) ? " id=\"" + id + "\"" : "") + ">\n");
		buf.append("<div class=\"infobox-header\">\n");
		buf.append(header);
		buf.append("</div>\n");
		buf.append("<div class=\"infobox-content\">\n");
	}

	private void writeBigEnding(StringBuffer buf) {
		buf.append("</div>\n");
		buf.append("</div>\n");
	}
	
	private void writeInsertBox(StringBuffer buf) {
		/* the insert file box */
		buf.append("<div class=\"infobox\">");
		buf.append("<div class=\"infobox-header\">Insert File</div>");
		buf.append("<div class=\"infobox-content\">");
		buf.append("<form action=\".\" method=\"post\" enctype=\"multipart/form-data\">");
		buf.append("<input type=\"hidden\" name=\"formPassword\" value=\"").append(node.formPassword).append("\" />");
		buf.append("Insert as: <input type=\"radio\" name=\"keytype\" value=\"chk\" checked /> CHK &nbsp; ");
		buf.append("<input type=\"radio\" name=\"keytype\" value=\"ksk\" /> KSK &nbsp; ");
		buf.append("<input type=\"text\" name=\"key\" value=\"KSK@\" /> &nbsp; ");
		buf.append("File: <input type=\"file\" name=\"filename\" value=\"\" /> &nbsp; ");
		buf.append("<input type=\"checkbox\" name=\"dontCompress\" /> Don&rsquo;t Compress &nbsp; ");
		buf.append("<input type=\"submit\" name=\"insert\" value=\"Insert File\" /> &nbsp; ");
		buf.append("<input type=\"reset\" value=\"Reset Form\" />");
		buf.append("</form>");
		buf.append("</div>");
		buf.append("</div>\n");
	}

	public String supportedMethods() {
		return "GET, POST";
	}

}
