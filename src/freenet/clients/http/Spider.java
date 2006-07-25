package freenet.clients.http;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import freenet.plugin.HttpPlugin;
import freenet.plugin.PluginManager;
import freenet.support.HTMLEncoder;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.io.Bucket;

/**
 * Spider. Produces an index.
 */
public class Spider implements HttpPlugin, ClientCallback, FoundURICallback {

	long tProducedIndex;

	// URIs visited, or fetching, or queued. Added once then forgotten about.
	private final HashSet visitedURIs = new HashSet();
	private final HashSet urisWithWords = new HashSet();
	private final HashSet failedURIs = new HashSet();
	private final HashSet queuedURISet = new HashSet();
	private final LinkedList queuedURIList = new LinkedList();
	private final HashMap runningFetchesByURI = new HashMap();
	private final HashMap urisByWord = new HashMap();
	private final HashMap titlesOfURIs = new HashMap();

	private static final int minTimeBetweenEachIndexRewriting = 10;

	// Can have many; this limit only exists to save memory.
	private static final int maxParallelRequests = 20;
	private int maxShownURIs = 50;

	private Node node;
	private FetcherContext ctx;
	private final short PRIORITY_CLASS = RequestStarter.PREFETCH_PRIORITY_CLASS;
	private boolean stopped = true;

	private synchronized void queueURI(FreenetURI uri) {
		String uriStr = null;
		
		/* We remove HTML targets from URI (http://my.server/file#target) */
		/* Else we re-index already indexed file */
		try {
			uriStr = uri.toString(false);
			if(uriStr.indexOf("#") > 0)
				{
					uriStr = uriStr.substring(0, uriStr.indexOf("#"));
					uri = new FreenetURI(uriStr);
				}
		} catch (MalformedURLException e) {
			Logger.error(this, "Spider: MalformedURLException: "+uriStr+":"+e);
			return;
		}

		
		if ((!visitedURIs.contains(uri)) && queuedURISet.add(uri)) {
			queuedURIList.addLast(uri);
			visitedURIs.add(uri);
		}
	}

	private void startSomeRequests() {
		ArrayList toStart = null;
		synchronized (this) {
			if (stopped) {
				return;
			}
			int running = runningFetchesByURI.size();
			int queued = queuedURIList.size();
			
			if ((running >= maxParallelRequests) || (queued == 0))
				return;
			
			toStart = new ArrayList(Math.min(maxParallelRequests - running, queued));
			
			for (int i = running; i < maxParallelRequests; i++) {
				if (queuedURIList.isEmpty())
					break;
				FreenetURI uri = (FreenetURI) queuedURIList.removeFirst();
				queuedURISet.remove(uri);
				ClientGetter getter = makeGetter(uri);
				toStart.add(getter);
			}
			for (int i = 0; i < toStart.size(); i++) {
				ClientGetter g = (ClientGetter) toStart.get(i);
				try {
					runningFetchesByURI.put(g.getURI(), g);
					g.start();
				} catch (FetchException e) {
					onFailure(e, g);
				}
			}
		}
	}

	private ClientGetter makeGetter(FreenetURI uri) {
		ClientGetter g = new ClientGetter(this, node.chkFetchScheduler, node.sskFetchScheduler, uri, ctx, PRIORITY_CLASS, this, null);
		return g;
	}

	public void onSuccess(FetchResult result, ClientGetter state) {
		FreenetURI uri = state.getURI();
		synchronized (this) {
			runningFetchesByURI.remove(uri);
		}
		startSomeRequests();
		ClientMetadata cm = result.getMetadata();
		Bucket data = result.asBucket();
		String mimeType = cm.getMIMEType();
		try {
			ContentFilter.filter(data, ctx.bucketFactory, mimeType, new URI("http://127.0.0.1:8888/" + uri.toString(false)), this);
		} catch (UnsafeContentTypeException e) {
			return; // Ignore
		} catch (IOException e) {
			Logger.error(this, "Bucket error?: " + e, e);
		} catch (URISyntaxException e) {
			Logger.error(this, "Internal error: " + e, e);
		} finally {
			data.free();
		}
	}

	public void onFailure(FetchException e, ClientGetter state) {
		FreenetURI uri = state.getURI();
		synchronized (this) {
			failedURIs.add(uri);
			runningFetchesByURI.remove(uri);
		}
		if (e.newURI != null)
			queueURI(e.newURI);
		else
			queueURI(uri);
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

	public void onText(String s, String type, URI baseURI) {

		FreenetURI uri;
		try {
			uri = new FreenetURI(baseURI.getPath());
		} catch (MalformedURLException e) {
			Logger.error(this, "Caught " + e, e);
			return;
		}

		if((type != null) && (type.length() != 0) && type.toLowerCase().equals("title")
		   && (s != null) && (s.length() != 0) && (s.indexOf('\n') < 0)) {
			/* We should have a correct title */
			titlesOfURIs.put(uri.toString(false), s);
		}


		String[] words = s.split("[^A-Za-z0-9]");
		for (int i = 0; i < words.length; i++) {
			String word = words[i];
			if ((word == null) || (word.length() == 0))
				continue;
			word = word.toLowerCase();
			addWord(word, uri);
		}
	}

	private synchronized void addWord(String word, FreenetURI uri) {
		FreenetURI[] uris = (FreenetURI[]) urisByWord.get(word);
		urisWithWords.add(uri);
		if (uris == null) {
			urisByWord.put(word, new FreenetURI[] { uri });
		} else {
			for (int i = 0; i < uris.length; i++) {
				if (uris[i].equals(uri))
					return;
			}
			FreenetURI[] newURIs = new FreenetURI[uris.length + 1];
			System.arraycopy(uris, 0, newURIs, 0, uris.length);
			newURIs[uris.length] = uri;
			urisByWord.put(word, newURIs);
		}
		if (tProducedIndex + minTimeBetweenEachIndexRewriting * 1000 < System.currentTimeMillis()) {
			try {
				produceIndex();
			} catch (IOException e) {
				Logger.error(this, "Caught " + e + " while creating index", e);
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
		if (urisByWord.isEmpty() || urisWithWords.isEmpty()) {
			System.out.println("No URIs with words");
			return;
		}
		String[] words = (String[]) urisByWord.keySet().toArray(new String[urisByWord.size()]);
		Arrays.sort(words);
		FreenetURI[] uris = (FreenetURI[]) urisWithWords.toArray(new FreenetURI[urisWithWords.size()]);
		HashMap urisToNumbers = new HashMap();
		for (int i = 0; i < uris.length; i++) {
			urisToNumbers.put(uris[i], new Integer(i));
			bw.write("!" + uris[i].toString(false) + "\n");
			bw.write("+" + titlesOfURIs.get(uris[i].toString(false)) + "\n");
		}
		for (int i = 0; i < words.length; i++) {
			StringBuffer s = new StringBuffer();
			s.append('?');
			s.append(words[i]);
			FreenetURI[] urisForWord = (FreenetURI[]) urisByWord.get(words[i]);
			for (int j = 0; j < urisForWord.length; j++) {
				FreenetURI uri = urisForWord[j];
				Integer x = (Integer) urisToNumbers.get(uri);
				if (x == null)
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

	/**
	 * @see freenet.plugin.HttpPlugin#handleGet(freenet.clients.http.HTTPRequest, freenet.clients.http.ToadletContext)
	 */
	public void handleGet(HTTPRequest request, ToadletContext context) throws IOException, ToadletContextClosedException {
		String action = request.getParam("action");
		PageMaker pageMaker = context.getPageMaker();
		if ((action == null) || (action.length() == 0)) {
			MultiValueTable responseHeaders = new MultiValueTable();
			responseHeaders.put("Location", "?action=list");
			context.sendReplyHeaders(301, "Redirect", responseHeaders, "text/html; charset=utf-8", 0);
			return;
		} else if ("list".equals(action)) {
			String listName = request.getParam("listName", null);
			StringBuffer responseBuffer = new StringBuffer();
			pageMaker.makeHead(responseBuffer, "The Definitive Spider");
			/* create copies for multi-threaded use */
			if (listName == null) {
				Map runningFetches = new HashMap(runningFetchesByURI);
				List queued = new ArrayList(queuedURIList);
				Set visited = new HashSet(visitedURIs);
				Set failed = new HashSet(failedURIs);
				responseBuffer.append(createNavbar(runningFetches.size(), queued.size(), visited.size(), failed.size()));
				responseBuffer.append(createAddBox());
				responseBuffer.append(createList("Running Fetches", "running", runningFetches.keySet(), maxShownURIs));
				responseBuffer.append(createList("Queued URIs", "queued", queued, maxShownURIs));
				responseBuffer.append(createList("Visited URIs", "visited", visited, maxShownURIs));
				responseBuffer.append(createList("Failed URIs", "failed", failed, maxShownURIs));
			} else {
				responseBuffer.append(createBackBox());
				if ("failed".equals(listName)) {
					Set failed = new HashSet(failedURIs);
					responseBuffer.append(createList("Failed URIs", "failed", failed, -1));	
				} else if ("visited".equals(listName)) {
					Set visited = new HashSet(visitedURIs);
					responseBuffer.append(createList("Visited URIs", "visited", visited, -1));
				} else if ("queued".equals(listName)) {
					List queued = new ArrayList(queuedURIList);
					responseBuffer.append(createList("Queued URIs", "queued", queued, -1));
				} else if ("running".equals(listName)) {
					Map runningFetches = new HashMap(runningFetchesByURI);
					responseBuffer.append(createList("Running Fetches", "running", runningFetches.keySet(), -1));
				}
			}
			pageMaker.makeTail(responseBuffer);
			MultiValueTable responseHeaders = new MultiValueTable();
			byte[] responseBytes = responseBuffer.toString().getBytes("utf-8");
			context.sendReplyHeaders(200, "OK", responseHeaders, "text/html; charset=utf-8", responseBytes.length);
			context.writeData(responseBytes);
		} else if ("add".equals(action)) {
			String uriParam = request.getParam("key");
			try {
				FreenetURI uri = new FreenetURI(uriParam);
				synchronized (this) {
					failedURIs.remove(uri);
					visitedURIs.remove(uri);
				}
				queueURI(uri);
				startSomeRequests();
			} catch (MalformedURLException mue1) {
				sendSimpleResponse(context, "URL invalid", "The given URI is not valid. Please <a href=\"?action=list\">return</a> and try again.");
				return;
			}
			MultiValueTable responseHeaders = new MultiValueTable();
			responseHeaders.put("Location", "?action=list");
			context.sendReplyHeaders(301, "Redirect", responseHeaders, "text/html; charset=utf-8", 0);
			return;
		}
	}

	/**
	 * @see freenet.plugin.HttpPlugin#handlePost(freenet.clients.http.HTTPRequest, freenet.clients.http.ToadletContext)
	 */
	public void handlePost(HTTPRequest request, ToadletContext context) throws IOException {
	}
	
	private void sendSimpleResponse(ToadletContext context, String title, String message) throws ToadletContextClosedException, IOException {
		PageMaker pageMaker = context.getPageMaker();
		StringBuffer outputBuffer = new StringBuffer();
		pageMaker.makeHead(outputBuffer, title);
		outputBuffer.append("<div class=\"infobox infobox-alert\">");
		outputBuffer.append("<div class=\"infobox-header\">").append(HTMLEncoder.encode(title)).append("</div>\n");
		outputBuffer.append("<div class=\"infobox-content\">").append(HTMLEncoder.encode(message)).append("</div>\n");
		outputBuffer.append("</div>\n");
		byte[] responseBytes = outputBuffer.toString().getBytes("utf-8");
		context.sendReplyHeaders(200, "OK", new MultiValueTable(), "text/html; charset=utf-8", responseBytes.length);
		context.writeData(responseBytes);
	}
	
	private StringBuffer createBackBox() {
		StringBuffer outputBuffer = new StringBuffer();
		outputBuffer.append("<div class=\"infobox\">");
		outputBuffer.append("<div class=\"infobox-content\">Return to the <a href=\"?action=list\">list of all URIs</a>.</div>");
		outputBuffer.append("</div>\n");
		return outputBuffer;
	}
	
	private StringBuffer createAddBox() {
		StringBuffer outputBuffer = new StringBuffer();
		outputBuffer.append("<div class=\"infobox\">");
		outputBuffer.append("<div class=\"infobox-header\">Add a URI</div>");
		outputBuffer.append("<div class=\"infobox-content\"><form action=\"\" method=\"get\">");
		outputBuffer.append("<input type=\"hidden\" name=\"action\" value=\"add\" />");
		outputBuffer.append("<input type=\"text\" size=\"40\" name=\"key\" value=\"\" />");
		outputBuffer.append("<input type=\"submit\" value=\"Add URI\" />");
		outputBuffer.append("</form></div>\n");
		outputBuffer.append("</div>\n");
		return outputBuffer;
	}

	private StringBuffer createNavbar(int running, int queued, int visited, int failed) {
		StringBuffer outputBuffer = new StringBuffer();
		outputBuffer.append("<div class=\"infobox navbar\">");
		outputBuffer.append("<div class=\"infobox-header\">Page navigation</div>");
		outputBuffer.append("<div class=\"infobox-content\"><ul>");
		outputBuffer.append("<li><a href=\"#running\">Running (").append(running).append(")</a></li>");
		outputBuffer.append("<li><a href=\"#queued\">Queued (").append(queued).append(")</a></li>");
		outputBuffer.append("<li><a href=\"#visited\">Visited (").append(visited).append(")</a></li>");
		outputBuffer.append("<li><a href=\"#failed\">Failed (").append(failed).append(")</a></li>");
		outputBuffer.append("</ul></div>\n");
		outputBuffer.append("</div>\n");
		return outputBuffer;
	}

	private StringBuffer createList(String listName, String anchorName, Collection collection, int maxCount) {
		StringBuffer outputBuffer = new StringBuffer();
		outputBuffer.append("<a name=\"").append(HTMLEncoder.encode(anchorName)).append("\"></a>");
		outputBuffer.append("<div class=\"infobox\">");
		outputBuffer.append("<div class=\"infobox-header\">").append(HTMLEncoder.encode(listName)).append(" (").append(collection.size()).append(")</div>\n");
		outputBuffer.append("<div class=\"infobox-content\">");
		Iterator collectionItems = collection.iterator();
		int itemCount = 0;
		while (collectionItems.hasNext()) {
			FreenetURI uri = (FreenetURI) collectionItems.next();
			outputBuffer.append(HTMLEncoder.encode(uri.toString())).append("<br/>\n");
			if (itemCount++ == maxCount) {
				outputBuffer.append("<br/><a href=\"?action=list&amp;listName=").append(HTMLEncoder.encode(anchorName)).append("\">Show all&hellip;</a>");
				break;
			}
		}
		outputBuffer.append("</div>\n");
		outputBuffer.append("</div>\n");
		return outputBuffer;
	}

	/**
	 * @see freenet.plugin.Plugin#getPluginName()
	 */
	public String getPluginName() {
		return "The Definitive Spider";
	}

	/**
	 * @see freenet.plugin.Plugin#setPluginManager(freenet.plugin.PluginManager)
	 */
	public void setPluginManager(PluginManager pluginManager) {
		this.node = pluginManager.getNode();
		this.ctx = node.makeClient((short) 0).getFetcherContext();
		ctx.maxSplitfileBlockRetries = 10;
		ctx.maxNonSplitfileRetries = 10;
		ctx.maxTempLength = 2 * 1024 * 1024;
		ctx.maxOutputLength = 2 * 1024 * 1024;
		tProducedIndex = System.currentTimeMillis();
	}


	/**
	 * @see freenet.plugin.Plugin#startPlugin()
	 */
	public void startPlugin() {
		FreenetURI[] initialURIs = node.bookmarkManager.getBookmarkURIs();
		for (int i = 0; i < initialURIs.length; i++)
			queueURI(initialURIs[i]);
		stopped = false;
		Thread starterThread = new Thread("Spider Plugin Starter") {
			public void run() {
				startSomeRequests();
			}
		};
		starterThread.setDaemon(true);
		starterThread.start();
	}

	/**
	 * @see freenet.plugin.Plugin#stopPlugin()
	 */
	public void stopPlugin() {
		synchronized (this) {
			stopped = true;
			queuedURIList.clear();
		}
	}

	public void onMajorProgress() {
		// Ignore
	}

}
