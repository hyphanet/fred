package freenet.clients.http;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Iterator;
import java.util.LinkedList;

import freenet.client.HighLevelSimpleClient;
import freenet.keys.FreenetURI;
import freenet.node.fcp.ClientGet;
import freenet.node.fcp.ClientPut;
import freenet.node.fcp.ClientPutBase;
import freenet.node.fcp.ClientPutDir;
import freenet.node.fcp.ClientRequest;
import freenet.node.fcp.FCPServer;
import freenet.support.Fields;
import freenet.support.HTMLEncoder;
import freenet.support.Logger;
import freenet.support.SizeUtil;
import freenet.support.URLEncoder;

public class QueueToadlet extends Toadlet {

	final FCPServer fcp;
	
	public QueueToadlet(FCPServer fcp, HighLevelSimpleClient client) {
		super(client);
		this.fcp = fcp;
		if(fcp == null) throw new NullPointerException();
	}
	
	public void handleGet(URI uri, ToadletContext ctx) 
	throws ToadletContextClosedException, IOException, RedirectException {
		
		StringBuffer buf = new StringBuffer(2048);
		
		ctx.getPageMaker().makeHead(buf, "Queued Requests");
		
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
				writeTableHead("Completed downloads to temporary space", new String[] { "Key", "Size", "Type", "Download", "Persistence", "Identifier" }, buf );
				for(Iterator i = completedDownloadToTemp.iterator();i.hasNext();) {
					ClientGet p = (ClientGet) i.next();
					writeRowStart(buf);
					writeKeyCell(p.getURI(), buf);
					writeSizeCell(p.getDataSize(), buf);
					writeTypeCell(p.getMIMEType(), buf);
					writeDownloadCell(p, buf);
					writePersistenceCell(p, buf);
					writeIdentifierCell(p, buf);
					writeRowEnd(buf);
				}
				writeTableEnd(buf);
			}
			
			if(!completedDownloadToDisk.isEmpty()) {
				writeTableHead("Completed downloads to disk", new String[] { "Key", "Filename", "Size", "Type", "Download", "Persistence", "Identifier" }, buf);
				for(Iterator i=completedDownloadToDisk.iterator();i.hasNext();) {
					ClientGet p = (ClientGet) i.next();
					writeRowStart(buf);
					writeKeyCell(p.getURI(), buf);
					writeFilenameCell(p.getDestFilename(), buf);
					writeSizeCell(p.getDataSize(), buf);
					writeTypeCell(p.getMIMEType(), buf);
					writeDownloadCell(p, buf);
					writePersistenceCell(p, buf);
					writeIdentifierCell(p, buf);
					writeRowEnd(buf);
				}
				writeTableEnd(buf);
			}

			if(!completedUpload.isEmpty()) {
				writeTableHead("Completed uploads", new String[] { "Key", "Filename", "Size", "Type", "Persistence", "Identifier" }, buf);
				for(Iterator i=completedUpload.iterator();i.hasNext();) {
					ClientPut p = (ClientPut) i.next();
					writeRowStart(buf);
					writeKeyCell(p.getFinalURI(), buf);
					if(p.isDirect())
						writeDirectCell(buf);
					else
						writeFilenameCell(p.getOrigFilename(), buf);
					writeSizeCell(p.getDataSize(), buf);
					writeTypeCell(p.getMIMEType(), buf);
					writePersistenceCell(p, buf);
					writeIdentifierCell(p, buf);
					writeRowEnd(buf);
				}
				writeTableEnd(buf);
			}
			
			if(!completedDirUpload.isEmpty()) {
				// FIXME include filename??
				writeTableHead("Completed directory uploads", new String[] { "Key", "Files", "Total Size", "Persistence", "Identifier" }, buf);
				for(Iterator i=completedUpload.iterator();i.hasNext();) {
					ClientPutDir p = (ClientPutDir) i.next();
					writeRowStart(buf);
					writeKeyCell(p.getFinalURI(), buf);
					writeNumberCell(p.getNumberOfFiles(), buf);
					writeSizeCell(p.getTotalDataSize(), buf);
					writePersistenceCell(p, buf);
					writeIdentifierCell(p, buf);
					writeRowEnd(buf);
				}
				writeTableEnd(buf);
			}
		}

		/* FIXME color-coded progress bars.
		 * It would be really nice to have a color-coded progress bar.
		 * We can then show what part is successful, what part isn't tried yet,
		 * what part has each different well known error code...
		 */
		
		if(!(failedDownload.isEmpty() && failedUpload.isEmpty())) {
			writeBigHeading("Failed requests", buf);
			if(!failedDownload.isEmpty()) {
				writeTableHead("Failed downloads", new String[] { "Key", "Filename", "Size", "Type", "Success", "Reason", "Persistence", "Identifier" }, buf);
				for(Iterator i=failedDownload.iterator();i.hasNext();) {
					ClientGet p = (ClientGet) i.next();
					writeRowStart(buf);
					writeKeyCell(p.getURI(), buf);
					if(p.isDirect())
						writeDirectCell(buf);
					else
						writeFilenameCell(p.getDestFilename(), buf);
					writeSizeCell(p.getDataSize(), buf);
					writeTypeCell(p.getMIMEType(), buf);
					writeSuccessFractionCell(p, buf);
					writeFailureReasonCell(p, buf);
					writePersistenceCell(p, buf);
					writeIdentifierCell(p, buf);
					writeRowEnd(buf);
				}
				writeTableEnd(buf);
			}
			
			if(!failedUpload.isEmpty()) {
				writeTableHead("Failed uploads", new String[] { "Key", "Filename", "Size", "Type", "Success", "Reason", "Persistence", "Identifier" }, buf);
				for(Iterator i=failedUpload.iterator();i.hasNext();) {
					ClientPut p = (ClientPut) i.next();
					writeRowStart(buf);
					writeKeyCell(p.getFinalURI(), buf);
					if(p.isDirect())
						writeDirectCell(buf);
					else
						writeFilenameCell(p.getOrigFilename(), buf);
					writeSizeCell(p.getDataSize(), buf);
					writeTypeCell(p.getMIMEType(), buf);
					writeSuccessFractionCell(p, buf);
					writeFailureReasonCell(p, buf);
					writePersistenceCell(p, buf);
					writeIdentifierCell(p, buf);
					writeRowEnd(buf);
				}
				writeTableEnd(buf);
			}
			
			if(!failedDirUpload.isEmpty()) {
				writeTableHead("Failed directory uploads", new String[] { "Key", "Files", "Total Size", "Success", "Reason", "Persistence", "Identifier" }, buf);
				for(Iterator i=failedDirUpload.iterator();i.hasNext();) {
					ClientPutDir p = (ClientPutDir) i.next();
					writeRowStart(buf);
					writeKeyCell(p.getFinalURI(), buf);
					writeNumberCell(p.getNumberOfFiles(), buf);
					writeSizeCell(p.getTotalDataSize(), buf);
					writeSuccessFractionCell(p, buf);
					writeFailureReasonCell(p, buf);
					writePersistenceCell(p, buf);
					writeIdentifierCell(p, buf);
					writeRowEnd(buf);
				}
				writeTableEnd(buf);
			}
		}
		
		if(!(uncompletedDownload.isEmpty() && uncompletedUpload.isEmpty() && 
				uncompletedDirUpload.isEmpty())) {
			writeBigHeading("Requests in progress", buf);
			if(!uncompletedDownload.isEmpty()) {
				writeTableHead("Downloads in progress", new String[] { "Key", "Filename", "Size", "Type", "Success", "Persistence", "Identifier" }, buf);
				for(Iterator i = uncompletedDownload.iterator();i.hasNext();) {
					ClientGet p = (ClientGet) i.next();
					writeRowStart(buf);
					writeKeyCell(p.getURI(), buf);
					if(p.isDirect())
						writeDirectCell(buf);
					else
						writeFilenameCell(p.getDestFilename(), buf);
					writeSizeCell(p.getDataSize(), buf);
					writeTypeCell(p.getMIMEType(), buf);
					writeSuccessFractionCell(p, buf);
					writePersistenceCell(p, buf);
					writeIdentifierCell(p, buf);
					writeRowEnd(buf);
				}
				writeTableEnd(buf);
			}
			
			if(!uncompletedUpload.isEmpty()) {
				writeTableHead("Uploads in progress", new String[] { "Key", "Filename", "Size", "Type", "Success", "Persistence", "Identifier" }, buf);
				for(Iterator i = uncompletedDownload.iterator();i.hasNext();) {
					ClientPut p = (ClientPut) i.next();
					writeRowStart(buf);
					writeKeyCell(p.getFinalURI(), buf);
					if(p.isDirect())
						writeDirectCell(buf);
					else
						writeFilenameCell(p.getOrigFilename(), buf);
					writeSizeCell(p.getDataSize(), buf);
					writeTypeCell(p.getMIMEType(), buf);
					writeSuccessFractionCell(p, buf);
					writePersistenceCell(p, buf);
					writeIdentifierCell(p, buf);
					writeRowEnd(buf);
				}
				writeTableEnd(buf);
			}
			
			if(!uncompletedDirUpload.isEmpty()) {
				writeTableHead("Directory uploads in progress", new String[] { "Key", "Files", "Total Size", "Success", "Persistence", "Identifier" }, buf);
				for(Iterator i=completedUpload.iterator();i.hasNext();) {
					ClientPutDir p = (ClientPutDir) i.next();
					writeRowStart(buf);
					writeKeyCell(p.getFinalURI(), buf);
					writeNumberCell(p.getNumberOfFiles(), buf);
					writeSizeCell(p.getTotalDataSize(), buf);
					writeSuccessFractionCell(p, buf);
					writePersistenceCell(p, buf);
					writeIdentifierCell(p, buf);
					writeRowEnd(buf);
				}
				writeTableEnd(buf);
			}
		}
		
		ctx.getPageMaker().makeTail(buf);
		
		this.writeReply(ctx, 200, "text/html", "OK", buf.toString());
	}

	
	private void writeFailureReasonCell(ClientRequest p, StringBuffer buf) {
		buf.append("<td>");
		String s = p.getFailureReason();
		if(s == null)
			buf.append("UNKNOWN");
		else
			buf.append(s);
		buf.append("</td>\n");
	}

	private void writeSuccessFractionCell(ClientRequest p, StringBuffer buf) {
		double frac = p.getSuccessFraction();
		if(frac < 0) {
			buf.append("<td>UNKNOWN</td>\n");
		} else {
			buf.append("<td>");
			buf.append(frac * 100);
			buf.append("%</td>\n");
		}
	}

	private void writeNumberCell(int numberOfFiles, StringBuffer buf) {
		buf.append("<td>");
		buf.append(numberOfFiles);
		buf.append("</td>\n");
	}

	private void writeDirectCell(StringBuffer buf) {
		buf.append("<td>DIRECT</td>\n");
	}

	private void writeFilenameCell(File destFilename, StringBuffer buf) {
		buf.append("<td>");
		if(destFilename == null)
			buf.append("NONE");
		else
			buf.append(HTMLEncoder.encode(destFilename.toString()));
		buf.append("</td>\n");
	}

	private void writeTableEnd(StringBuffer buf) {
		buf.append("</table>");
	}

	private void writeRowEnd(StringBuffer buf) {
		buf.append("</tr>\n");
	}

	private void writeIdentifierCell(ClientRequest p, StringBuffer buf) {
		buf.append("<td>");
		buf.append(HTMLEncoder.encode(p.getIdentifier()));
		buf.append("</td>\n");
	}

	private void writePersistenceCell(ClientRequest p, StringBuffer buf) {
		buf.append("<td>");
		if(!p.isPersistent())
			buf.append("<font color=\"black\">NONE</font>");
		else if(!p.isPersistentForever())
			buf.append("<font color=\"blue\">REBOOT</font>");
		else
			buf.append("<font color=\"green\">FOREVER</font>");
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
			buf.append(type);
		else
			buf.append("UNKNOWN");
		buf.append("</td>\n");
	}

	private void writeSizeCell(long dataSize, StringBuffer buf) {
		buf.append("<td>");
		if(dataSize >= 0)
			buf.append(SizeUtil.formatSize(dataSize));
		else
			buf.append("UNKNOWN");
		buf.append("</td>\n");
	}

	private void writeKeyCell(FreenetURI uri, StringBuffer buf) {
		buf.append("<td>");
		buf.append("<a href=\"/");
		String u = uri.toString(false);
		buf.append(URLEncoder.encode(u));
		buf.append("\">");
		// FIXME too long? maybe only show the human readable bit?
		buf.append(HTMLEncoder.encode(u));
		buf.append("</a></td>\n");
	}

	private void writeRowStart(StringBuffer buf) {
		buf.append("<tr>");
	}

	private void writeTableHead(String string, String[] strings, StringBuffer buf) {
		buf.append("<h2>");
		buf.append(string);
		buf.append("</h2>\n");
		buf.append("<table border=\"0\">\n");
		buf.append("<tr>");
		for(int i=0;i<strings.length;i++) {
			buf.append("<th>");
			buf.append(strings[i]);
			buf.append("</th>");
		}
		buf.append("\n");
	}

	private void writeBigHeading(String string, StringBuffer buf) {
		buf.append("<h1>");
		buf.append(string);
		buf.append("</h1>\n");
	}

	public String supportedMethods() {
		// TODO Auto-generated method stub
		return null;
	}

}
