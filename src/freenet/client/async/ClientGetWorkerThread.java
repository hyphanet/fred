/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.client.async;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.filter.ContentFilter;
import freenet.client.filter.ContentFilter.FilterStatus;
import freenet.crypt.HashResult;
import freenet.crypt.MultiHashInputStream;
import freenet.keys.FreenetURI;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;

class ClientGetWorkerThread extends Thread {

	private InputStream input;
	final private FreenetURI uri;
	final private HashResult[] hashes;
	final private FetchContext ctx;
	private String mimeType;
	private OutputStream output;
	private boolean finished = false;
	private Throwable error = null;

	private static volatile boolean logMINOR;

	static {
		Logger.registerClass(ClientGetWorkerThread.class);
	}

	ClientGetWorkerThread(PipedInputStream input, FreenetURI uri, String mimeType, Bucket destination, HashResult[] hashes, FetchContext ctx) throws IOException {
		super("ClientGetWorkerThread");
		this.input = input;
		this.ctx = ctx;
		this.uri = uri;
		this.mimeType = mimeType;
		this.hashes = hashes;
		output = destination.getOutputStream();
	}

	@Override
	public void run() {
		try {
			//Validate the hash of the now decompressed data
			input = new BufferedInputStream(input);
			MultiHashInputStream hashStream = null;
			if(hashes != null) {
				hashStream = new MultiHashInputStream(input, HashResult.makeBitmask(hashes));
				input = hashStream;
			}
			//Filter the data, if we are supposed to
			if(ctx.filterData){
				if(logMINOR) Logger.minor(this, "Running content filter... Prefetch hook: "+ctx.prefetchHook+" tagReplacer: "+ctx.tagReplacer);
				try {
					if(ctx.overrideMIME != null) mimeType = ctx.overrideMIME;
					// Send XHTML as HTML because we can't use web-pushing on XHTML.
					if(mimeType != null && mimeType.compareTo("application/xhtml+xml") == 0) mimeType = "text/html";
					FilterStatus filterStatus = ContentFilter.filter(input, output, mimeType, uri.toURI("/"), ctx.prefetchHook, ctx.tagReplacer, ctx.charset);
					input.close();
					output.close();
					String detectedMIMEType = filterStatus.mimeType.concat(filterStatus.charset == null ? "" : "; charset="+filterStatus.charset);
					//clientMetadata = new ClientMetadata(detectedMIMEType);
				} finally {
					Closer.close(input);
					Closer.close(output);
				}
			}
			else {
				if(logMINOR) Logger.minor(this, "Ignoring content filter. The final result has not been written. Writing now.");
				try {
					FileUtil.copy(input, output, -1);
					input.close();
					output.close();
				} finally {
					Closer.close(input);
					Closer.close(output);
				}
			}
			if(hashes != null) {
				HashResult[] results = hashStream.getResults();
				if(!HashResult.strictEquals(results, hashes)) {
					throw new FetchException(FetchException.CONTENT_HASH_FAILED);
				}
			}

			onFinish();
		} catch(Throwable t) {
			Logger.error(this, "Exception caught while processing fetch: "+t,t);
			setError(t);
		}
	}

	public synchronized void setError(Throwable t) {
		error = t;
		onFinish();
	}

	public synchronized void getError() throws Throwable {
		if(error != null) throw error;
	}
	/** Marks that all work has finished, and wakes blocked threads.*/
	public synchronized void onFinish() {
		finished = true;
		notifyAll();
	}

	/** Blocks until all threads have finished executing and cleaning up.*/
	public synchronized void waitFinished() throws Throwable {
		while(!finished) {
			try {
				wait();
			} catch(InterruptedException e) {
				//Do nothing
			}
		}
		getError();
	}
}
