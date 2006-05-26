package freenet.clients.http;

import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Vector;

import freenet.client.ClientMetadata;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.FetcherContext;
import freenet.client.InserterException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientCallback;
import freenet.client.async.ClientGetter;
import freenet.clients.http.filter.ContentFilter;
import freenet.clients.http.filter.FoundURICallback;
import freenet.clients.http.filter.UnsafeContentTypeException;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.node.RequestStarter;
import freenet.support.Bucket;
import freenet.support.Logger;

/**
 * Spider. Produces an index.
 */
public class Spider implements ClientCallback, FoundURICallback {
	
	long tProducedIndex;
	
	// URIs visited, or fetching, or queued. Added once then forgotten about.
	private final HashSet visitedURIs = new HashSet();
	private final HashSet urisWithWords = new HashSet();
	private final HashSet failedURIs = new HashSet();
	private final HashSet queuedURISet = new HashSet();
	private final LinkedList queuedURIList = new LinkedList();
	private final HashMap runningFetchesByURI = new HashMap();
	private final HashMap urisByWord = new HashMap();
	// Can have many; this limit only exists to save memory.
	private final int maxParallelRequests = 200;
	private final Node node;
	private final FetcherContext ctx;
	private final short PRIORITY_CLASS = RequestStarter.PREFETCH_PRIORITY_CLASS;
	
	public Spider(BookmarkManager bm, Node node) {
		this.node = node;
		this.ctx = node.makeClient((short)0).getFetcherContext();
		ctx.maxSplitfileBlockRetries = 10;
		ctx.maxNonSplitfileRetries = 10;
		ctx.maxTempLength = 2*1024*1024;
		ctx.maxOutputLength = 2*1024*1024;
		FreenetURI[] initialURIs = bm.getBookmarkURIs();
		for(int i=0;i<initialURIs.length;i++)
			queueURI(initialURIs[i]);
		tProducedIndex = System.currentTimeMillis();
		startSomeRequests();
	}

	private synchronized void queueURI(FreenetURI uri) {
		if((!visitedURIs.contains(uri)) && queuedURISet.add(uri)) {
			Logger.minor(this, "Spider queueing URI: "+uri);
			queuedURIList.addLast(uri);
			visitedURIs.add(uri);
		}
	}

	private void startSomeRequests() {
		Vector toStart = null;
		synchronized(this) {
			int running = runningFetchesByURI.size();
			int queued = queuedURIList.size();
			if(running == maxParallelRequests || queued == 0) return;
			if(toStart == null)
				toStart = new Vector(Math.min(maxParallelRequests-running, queued));
			for(int i=running;i<maxParallelRequests;i++) {
				if(queuedURIList.isEmpty()) break;
				FreenetURI uri = (FreenetURI) queuedURIList.removeFirst();
				queuedURISet.remove(uri);
				ClientGetter getter = makeGetter(uri);
				toStart.add(getter);
			}
		}
		if(toStart != null) {
			for(int i=0;i<toStart.size();i++) {
				ClientGetter g = (ClientGetter) toStart.get(i);
				try {
					Logger.minor(this, "Starting "+g+" for "+g.getURI());
					g.start();
					Logger.minor(this, "Started "+g+" for "+g.getURI());
					runningFetchesByURI.put(g.getURI(), g);
				} catch (FetchException e) {
					onFailure(e, g);
				}
			}
		}
	}

	private ClientGetter makeGetter(FreenetURI uri) {
		Logger.minor(this, "Starting getter for "+uri);
		ClientGetter g = new ClientGetter(this, node.chkFetchScheduler, node.sskFetchScheduler, uri, ctx, PRIORITY_CLASS, this, null);
		return g;
	}

	public void onSuccess(FetchResult result, ClientGetter state) {
		FreenetURI uri = state.getURI();
		synchronized(this) {
			runningFetchesByURI.remove(uri);
		}
		Logger.minor(this, "Success: "+uri);
		startSomeRequests();
		ClientMetadata cm = result.getMetadata();
		Bucket data = result.asBucket();
		String mimeType = cm.getMIMEType();
		try {
			ContentFilter.filter(data, ctx.bucketFactory, mimeType, new URI("http://127.0.0.1:8888/"+uri.toString(false)), this);
		} catch (UnsafeContentTypeException e) {
			return; // Ignore
		} catch (IOException e) {
			Logger.error(this, "Bucket error?: "+e, e);
		} catch (URISyntaxException e) {
			Logger.error(this, "Internal error: "+e, e);
		} finally {
			data.free();
		}
	}

	public void onFailure(FetchException e, ClientGetter state) {
		FreenetURI uri = state.getURI();
		Logger.minor(this, "Failed: "+uri);
		synchronized(this) {
			failedURIs.add(uri);
			runningFetchesByURI.remove(uri);
		}
		if(e.newURI != null)
			queueURI(e.newURI);
		startSomeRequests();
	}

	public void onSuccess(BaseClientPutter state) {
		// Ignore
	}

	public void onFailure(InserterException e, BaseClientPutter state) {
		// Ignore
	}

	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state) {
		// Ignore
	}

	public void foundURI(FreenetURI uri) {
		queueURI(uri);
		startSomeRequests();
	}

	public void onText(String s, URI baseURI) {
		FreenetURI uri;
		try {
			uri = new FreenetURI(baseURI.getPath());
		} catch (MalformedURLException e) {
			Logger.error(this, "Caught "+e, e);
			return;
		}
		String[] words = s.split("[^A-Za-z0-9]");
		for(int i=0;i<words.length;i++) {
			String word = words[i];
			if(word == null || word.length() == 0) continue;
			word = word.toLowerCase();
			addWord(word, uri);
		}
	}

	private synchronized void addWord(String word, FreenetURI uri) {
		FreenetURI[] uris = (FreenetURI[]) urisByWord.get(word);
		urisWithWords.add(uri);
		if(uris == null) {
			urisByWord.put(word, new FreenetURI[] { uri });
		} else {
			for(int i=0;i<uris.length;i++) {
				if(uris[i].equals(uri))
					return;
			}
			FreenetURI[] newURIs = new FreenetURI[uris.length+1];
			System.arraycopy(uris, 0, newURIs, 0, uris.length);
			newURIs[uris.length] = uri;
			urisByWord.put(word, newURIs);
		}
		Logger.minor(this, "Added word: "+word+" for "+uri);
		if(tProducedIndex + 10*1000 < System.currentTimeMillis()) {
			try {
				produceIndex();
			} catch (IOException e) {
				Logger.error(this, "Caught "+e+" while creating index", e);
			}
			tProducedIndex = System.currentTimeMillis();
		}
	}

	private synchronized void produceIndex() throws IOException {
		// Produce an index file.
		FileOutputStream fos = new FileOutputStream("index.new");
		OutputStreamWriter osw;
		try {
			osw = new OutputStreamWriter(fos, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new Error(e);
		}
		BufferedWriter bw = new BufferedWriter(osw);
		if(urisByWord.isEmpty() || urisWithWords.isEmpty()) {
			Logger.minor(this, "No URIs with words");
			return;
		}
		String[] words = (String[]) urisByWord.keySet().toArray(new String[urisByWord.size()]);
		Arrays.sort(words);
		FreenetURI[] uris = (FreenetURI[]) urisWithWords.toArray(new FreenetURI[urisWithWords.size()]);
		HashMap urisToNumbers = new HashMap();
		for(int i=0;i<uris.length;i++) {
			urisToNumbers.put(uris[i], new Integer(i));
			bw.write("!" + uris[i].toString(false)+"\n");
		}
		for(int i=0;i<words.length;i++) {
			StringBuffer s = new StringBuffer();
			s.append('?');
			s.append(words[i]);
			FreenetURI[] urisForWord = (FreenetURI[]) urisByWord.get(words[i]);
			for(int j=0;j<urisForWord.length;j++) {
				FreenetURI uri = urisForWord[j];
				Integer x = (Integer) urisToNumbers.get(uri);
				if(x == null)
					Logger.error(this, "Eh?");
				else {
					s.append(' ');
					s.append(x.toString());
				}
			}
			s.append('\n');
			bw.write(s.toString());
		}
		bw.close();
	}
	
}
