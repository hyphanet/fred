package freenet.clients.http;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.text.NumberFormat;
import java.util.Iterator;
import java.util.LinkedList;

import freenet.client.HighLevelSimpleClient;
import freenet.keys.FreenetURI;
import freenet.node.fcp.ClientGet;
import freenet.node.fcp.ClientPut;
import freenet.node.fcp.ClientPutDir;
import freenet.node.fcp.ClientRequest;
import freenet.node.fcp.FCPServer;
import freenet.node.fcp.MessageInvalidException;
import freenet.node.Node;
import freenet.support.Bucket;
import freenet.support.HTMLDecoder;
import freenet.support.HTMLEncoder;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.SizeUtil;
import freenet.support.URLEncoder;

public class QueueToadlet extends Toadlet {

	private Node node;
	final FCPServer fcp;
	
	public QueueToadlet(Node n, FCPServer fcp, HighLevelSimpleClient client) {
		super(client);
		this.node = n;
		this.fcp = fcp;
		if(fcp == null) throw new NullPointerException();
	}
	
	public void handlePost(URI uri, Bucket data, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		if(data.size() > 1024*1024) {
			this.writeReply(ctx, 400, "text/plain", "Too big", "Data exceeds 1MB limit");
			return;
		}
		HTTPRequest request = new HTTPRequest(uri, data, ctx);
		
		String pass = request.getParam("formPassword");
		if(pass == null || !pass.equals(node.formPassword)) {
			MultiValueTable headers = new MultiValueTable();
			headers.put("Location", "/queue/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		}
		
		if(request.isParameterSet("remove_request") && request.getParam("remove_request").length() > 0) {
			String identifier = request.getParam("identifier");
			Logger.minor(this, "Removing "+identifier);
			try {
				fcp.removeGlobalRequest(HTMLDecoder.decode(identifier));
			} catch (MessageInvalidException e) {
				this.sendErrorPage(ctx, 200, "Failed to remove request", "Failed to remove "+HTMLEncoder.encode(identifier)+" : "+HTMLEncoder.encode(e.getMessage()));
			}
			writePermanentRedirect(ctx, "Done", "/queue/");
			return;
		}
		if(request.isParameterSet("download")) {
			// Queue a download
			if(!request.isParameterSet("key")) {
				writeError("No key specified to download", "No key specified to download");
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
				writeError("Invalid URI to download", "Invalid URI to download");
				return;
			}
			String persistence = request.getParam("persistence");
			String returnType = request.getParam("return-type");
			fcp.makePersistentGlobalRequest(fetchURI, expectedMIMEType, persistence, returnType);
			writePermanentRedirect(ctx, "Done", "/queue/");
			return;
		}
		this.handleGet(uri, ctx);
	}
	
	private void writeError(String string, String string2) {
		// TODO Auto-generated method stub
		
	}

	public void handleGet(URI uri, ToadletContext ctx) 
	throws ToadletContextClosedException, IOException, RedirectException {
		
		StringBuffer buf = new StringBuffer(2048);
		
		ctx.getPageMaker().makeHead(buf, "Queued Requests");
		
		node.alerts.toSummaryHtml(buf);
		
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
		
		if(!(completedDownloadToTemp.isEmpty() && completedDownloadToDisk.isEmpty() &&
				completedUpload.isEmpty() && completedDirUpload.isEmpty())) {
			writeBigHeading("Completed requests", buf);
			
			if(!completedDownloadToTemp.isEmpty()) {
				writeTableHead("Completed downloads to temporary space", new String[] { "", "Identifier", "Size", "MIME-Type", "Download", "Persistence", "Key" }, buf );
				for(Iterator i = completedDownloadToTemp.iterator();i.hasNext();) {
					ClientGet p = (ClientGet) i.next();
					writeRowStart(buf);
					writeDeleteCell(p, buf);
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
				writeTableHead("Completed downloads to disk", new String[] { "", "Identifier", "Filename", "Size", "MIME-Type", "Download", "Persistence", "Key" }, buf);
				for(Iterator i=completedDownloadToDisk.iterator();i.hasNext();) {
					ClientGet p = (ClientGet) i.next();
					writeRowStart(buf);
					writeDeleteCell(p, buf);
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
				writeTableHead("Completed uploads", new String[] { "", "Key", "Filename", "Size", "MIME-Type", "Persistence", "Identifier" }, buf);
				for(Iterator i=completedUpload.iterator();i.hasNext();) {
					ClientPut p = (ClientPut) i.next();
					writeRowStart(buf);
					writeDeleteCell(p, buf);
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
				writeTableHead("Completed directory uploads", new String[] { "", "Identifier", "Files", "Total Size", "Persistence", "Key" }, buf);
				for(Iterator i=completedDirUpload.iterator();i.hasNext();) {
					ClientPutDir p = (ClientPutDir) i.next();
					writeRowStart(buf);
					writeDeleteCell(p, buf);
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

		/* FIXME color-coded progress bars.
		 * It would be really nice to have a color-coded progress bar.
		 * We can then show what part is successful, what part isn't tried yet,
		 * what part has each different well known error code...
		 */
		
		if(!(failedDownload.isEmpty() && failedUpload.isEmpty())) {
			writeBigHeading("Failed requests", buf);
			if(!failedDownload.isEmpty()) {
				writeTableHead("Failed downloads", new String[] { "", "Identifier", "Filename", "Size", "MIME-Type", "Progress", "Reason", "Persistence", "Key" }, buf);
				for(Iterator i=failedDownload.iterator();i.hasNext();) {
					ClientGet p = (ClientGet) i.next();
					writeRowStart(buf);
					writeDeleteCell(p, buf);
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
				writeTableHead("Failed uploads", new String[] { "", "Identifier", "Filename", "Size", "MIME-Type", "Progress", "Reason", "Persistence", "Key" }, buf);
				for(Iterator i=failedUpload.iterator();i.hasNext();) {
					ClientPut p = (ClientPut) i.next();
					writeRowStart(buf);
					writeDeleteCell(p, buf);
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
				writeTableHead("Failed directory uploads", new String[] { "", "Identifier", "Files", "Total Size", "Progress", "Reason", "Persistence", "Key" }, buf);
				for(Iterator i=failedDirUpload.iterator();i.hasNext();) {
					ClientPutDir p = (ClientPutDir) i.next();
					writeRowStart(buf);
					writeDeleteCell(p, buf);
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
			writeBigHeading("Requests in progress", buf);
			if(!uncompletedDownload.isEmpty()) {
				writeTableHead("Downloads in progress", new String[] { "", "Identifier", "Filename", "Size", "MIME-Type", "Progress", "Persistence", "Key" }, buf);
				for(Iterator i = uncompletedDownload.iterator();i.hasNext();) {
					ClientGet p = (ClientGet) i.next();
					writeRowStart(buf);
					writeDeleteCell(p, buf);
					writeIdentifierCell(p, p.getURI(), buf);
					if(p.isDirect())
						writeDirectCell(buf);
					else
						writeFilenameCell(p.getDestFilename(), buf);
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
				writeTableHead("Uploads in progress", new String[] { "", "Identifier", "Filename", "Size", "MIME-Type", "Progress", "Persistence", "Key" }, buf);
				for(Iterator i = uncompletedUpload.iterator();i.hasNext();) {
					ClientPut p = (ClientPut) i.next();
					writeRowStart(buf);
					writeDeleteCell(p, buf);
					writeIdentifierCell(p, p.getFinalURI(), buf);
					if(p.isDirect())
						writeDirectCell(buf);
					else
						writeFilenameCell(p.getOrigFilename(), buf);
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
				writeTableHead("Directory uploads in progress", new String[] { "", "Identifier", "Files", "Total Size", "Progress", "Persistence", "Key" }, buf);
				for(Iterator i=uncompletedDirUpload.iterator();i.hasNext();) {
					ClientPutDir p = (ClientPutDir) i.next();
					writeRowStart(buf);
					writeDeleteCell(p, buf);
					writeIdentifierCell(p, p.getFinalURI(), buf);
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
		double frac = p.getSuccessFraction();
		boolean b = p.isTotalFinalized();
		if(frac < 0) {
			buf.append("<span class=\"progress_fraction_unknown\">unknown</span>");
		} else {
			NumberFormat nf = NumberFormat.getInstance();
			nf.setMaximumFractionDigits(1);
			buf.append("<table class=\"progressbar\"><tr class=\"progressbar-tr\">"+
					"<td class=\"progressbar-done\" width=\""+nf.format(frac*100)+"%\"/>"+
					"<td class=\"progressbar-remaining\"/></tr></table>");
			
			if(b)
				buf.append("<span class=\"progress_fraction_finalized\">");
			else
				buf.append("<span class=\"progress_fraction_not_finalized\">");
			buf.append(nf.format(frac*100));
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

	private void writeDeleteCell(ClientRequest p, StringBuffer buf) {
		buf.append("<td>");
		buf.append("<form action=\"/queue/\" method=\"post\">");
		buf.append("<input type=\"hidden\" name=\"formPassword\" value=\""+node.formPassword+"\">");
		buf.append("<input type=\"hidden\" name=\"identifier\" value=\"");
		buf.append(HTMLEncoder.encode(p.getIdentifier()));
		buf.append("\"><input type=\"submit\" name=\"remove_request\" value=\"Delete\">");
		buf.append("</form>\n");
		buf.append("</td>\n");
	}
	
	private void writeIdentifierCell(ClientRequest p, FreenetURI uri, StringBuffer buf) {
		buf.append("<td>");
		if(uri != null) {
			buf.append("<span class=\"identifier_with_uri\"><a href=\"/");
			buf.append(uri.toString(false));
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
			// FIXME too long? maybe only show the human readable bit?
			buf.append(HTMLEncoder.encode(u));
			buf.append("</a></span>");
		} else {
			buf.append("<span class=\"key_unknown\">unknown</span>");
		}
		buf.append("</td>\n");
	}

	private void writeRowStart(StringBuffer buf) {
		buf.append("<tr>");
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

	private void writeBigHeading(String header, StringBuffer buf) {
		buf.append("<div class=\"infobox infobox-normal\">\n");
		buf.append("<div class=\"infobox-header\">\n");
		buf.append(header);
		buf.append("</div>\n");
		buf.append("<div class=\"infobox-content\">\n");
	}

	private void writeBigEnding(StringBuffer buf) {
		buf.append("</div>\n");
		buf.append("</div>\n");
	}

	public String supportedMethods() {
		return "GET, POST";
	}

}
