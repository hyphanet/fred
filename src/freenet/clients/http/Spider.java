/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
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
import freenet.node.NodeClientCore;
import freenet.node.RequestStarter;
import freenet.oldplugins.plugin.HttpPlugin;
import freenet.oldplugins.plugin.PluginManager;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.api.Bucket;
import freenet.support.api.HTTPRequest;

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

	private NodeClientCore core;
	private FetcherContext ctx;
	private final short PRIORITY_CLASS = RequestStarter.PREFETCH_PRIORITY_CLASS;
	private boolean stopped = true;

	private synchronized void queueURI(FreenetURI uri) {
		if ((!visitedURIs.contains(uri)) && queuedURISet.add(uri)) {
			queuedURIList.addLast(uri);
			visitedURIs.add(uri);
		}
	}

	private void startSomeRequests() {
		try{
			Thread.sleep(30 * 1000); // Let the node start up
		} catch (InterruptedException e){}
		
		FreenetURI[] initialURIs = core.bookmarkManager.getBookmarkURIs();
		for (int i = 0; i < initialURIs.length; i++)
			queueURI(initialURIs[i]);
		
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
		ClientGetter g = new ClientGetter(this, core.requestStarters.chkFetchScheduler, core.requestStarters.sskFetchScheduler, uri, ctx, PRIORITY_CLASS, this, null);
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
			ContentFilter.filter(data, ctx.bucketFactory, mimeType, uri.toURI("http://127.0.0.1:8888/"), this);
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
			titlesOfURIs.put(uri.toString(), s);
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
			bw.write('!' + uris[i].toString() + '\n');
			bw.write("+" + titlesOfURIs.get(uris[i].toString()) + '\n');
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
	 * @see freenet.oldplugins.plugin.HttpPlugin#handleGet(freenet.clients.http.HTTPRequestImpl, freenet.clients.http.ToadletContext)
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
			HTMLNode pageNode = pageMaker.getPageNode("The Definitive Spider");
			HTMLNode contentNode = pageMaker.getContentNode(pageNode);
			/* create copies for multi-threaded use */
			if (listName == null) {
				Map runningFetches = new HashMap(runningFetchesByURI);
				List queued = new ArrayList(queuedURIList);
				Set visited = new HashSet(visitedURIs);
				Set failed = new HashSet(failedURIs);
				contentNode.addChild(createNavbar(pageMaker, runningFetches.size(), queued.size(), visited.size(), failed.size()));
				contentNode.addChild(createAddBox(pageMaker, context));
				contentNode.addChild(createList(pageMaker, "Running Fetches", "running", runningFetches.keySet(), maxShownURIs));
				contentNode.addChild(createList(pageMaker, "Queued URIs", "queued", queued, maxShownURIs));
				contentNode.addChild(createList(pageMaker, "Visited URIs", "visited", visited, maxShownURIs));
				contentNode.addChild(createList(pageMaker, "Failed URIs", "failed", failed, maxShownURIs));
			} else {
				contentNode.addChild(createBackBox(pageMaker));
				if ("failed".equals(listName)) {
					Set failed = new HashSet(failedURIs);
					contentNode.addChild(createList(pageMaker, "Failed URIs", "failed", failed, -1));	
				} else if ("visited".equals(listName)) {
					Set visited = new HashSet(visitedURIs);
					contentNode.addChild(createList(pageMaker, "Visited URIs", "visited", visited, -1));
				} else if ("queued".equals(listName)) {
					List queued = new ArrayList(queuedURIList);
					contentNode.addChild(createList(pageMaker, "Queued URIs", "queued", queued, -1));
				} else if ("running".equals(listName)) {
					Map runningFetches = new HashMap(runningFetchesByURI);
					contentNode.addChild(createList(pageMaker, "Running Fetches", "running", runningFetches.keySet(), -1));
				}
			}
			MultiValueTable responseHeaders = new MultiValueTable();
			byte[] responseBytes = pageNode.generate().getBytes("utf-8");
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
				sendSimpleResponse(context, "URL invalid", "The given URI is not valid.");
				return;
			}
			MultiValueTable responseHeaders = new MultiValueTable();
			responseHeaders.put("Location", "?action=list");
			context.sendReplyHeaders(301, "Redirect", responseHeaders, "text/html; charset=utf-8", 0);
			return;
		}
	}

	/**
	 * @see freenet.oldplugins.plugin.HttpPlugin#handlePost(freenet.clients.http.HTTPRequestImpl, freenet.clients.http.ToadletContext)
	 */
	public void handlePost(HTTPRequest request, ToadletContext context) throws IOException {
	}
	
	private void sendSimpleResponse(ToadletContext context, String title, String message) throws ToadletContextClosedException, IOException {
		PageMaker pageMaker = context.getPageMaker();
		HTMLNode pageNode = pageMaker.getPageNode(title);
		HTMLNode contentNode = pageMaker.getContentNode(pageNode);
		HTMLNode infobox = contentNode.addChild(pageMaker.getInfobox("infobox-alter", title));
		HTMLNode infoboxContent = pageMaker.getContentNode(infobox);
		infoboxContent.addChild("#", message);
		byte[] responseBytes = pageNode.generate().getBytes("utf-8");
		context.sendReplyHeaders(200, "OK", new MultiValueTable(), "text/html; charset=utf-8", responseBytes.length);
		context.writeData(responseBytes);
	}
	
	private HTMLNode createBackBox(PageMaker pageMaker) {
		HTMLNode backbox = pageMaker.getInfobox((String) null);
		HTMLNode backContent = pageMaker.getContentNode(backbox);
		backContent.addChild("#", "Return to the ");
		backContent.addChild("a", "href", "?action=list", "list of all URIs");
		backContent.addChild("#", ".");
		return backbox;
	}
	
	private HTMLNode createAddBox(PageMaker pageMaker, ToadletContext ctx) {
		HTMLNode addBox = pageMaker.getInfobox("Add a URI");
		HTMLNode formNode = pageMaker.getContentNode(addBox).addChild("form", new String[] { "action", "method" }, new String[] { "", "get" });
		formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "action", "add" });
		formNode.addChild("input", new String[] { "type", "size", "name", "value" }, new String[] { "text", "40", "key", "" });
		formNode.addChild("input", new String[] { "type", "value" }, new String[] { "submit", "Add URI" });
		return addBox;
	}

	private HTMLNode createNavbar(PageMaker pageMaker, int running, int queued, int visited, int failed) {
		HTMLNode navbar = pageMaker.getInfobox("navbar", "Page Navigation");
		HTMLNode list = pageMaker.getContentNode(navbar).addChild("ul");
		list.addChild("li").addChild("a", "href", "#running", "Running (" + running + ')');
		list.addChild("li").addChild("a", "href", "#queued", "Queued (" + queued + ')');
		list.addChild("li").addChild("a", "href", "#visited", "Visited (" + visited + ')');
		list.addChild("li").addChild("a", "href", "#failed", "Failed (" + failed + ')');
		return navbar;
	}

	private HTMLNode createList(PageMaker pageMaker, String listName, String anchorName, Collection collection, int maxCount) {
		HTMLNode listNode = new HTMLNode("div");
		listNode.addChild("a", "name", anchorName);
		HTMLNode listBox = pageMaker.getInfobox(listName);
		HTMLNode listContent = pageMaker.getContentNode(listBox);
		listNode.addChild(listBox);
		Iterator collectionItems = collection.iterator();
		int itemCount = 0;
		while (collectionItems.hasNext()) {
			FreenetURI uri = (FreenetURI) collectionItems.next();
			listContent.addChild("#", uri.toString());
			listContent.addChild("br");
			if (itemCount++ == maxCount) {
				listContent.addChild("br");
				listContent.addChild("a", "href", "?action=list&listName=" + anchorName, "Show all\u2026");
				break;
			}
		}
		return listNode;
	}

	/**
	 * @see freenet.oldplugins.plugin.Plugin#getPluginName()
	 */
	public String getPluginName() {
		return "The Definitive Spider";
	}

	/**
	 * @see freenet.oldplugins.plugin.Plugin#setPluginManager(freenet.oldplugins.plugin.PluginManager)
	 */
	public void setPluginManager(PluginManager pluginManager) {
		this.core = pluginManager.getClientCore();
		this.ctx = core.makeClient((short) 0).getFetcherContext();
		ctx.maxSplitfileBlockRetries = 10;
		ctx.maxNonSplitfileRetries = 10;
		ctx.maxTempLength = 2 * 1024 * 1024;
		ctx.maxOutputLength = 2 * 1024 * 1024;
		tProducedIndex = System.currentTimeMillis();
	}


	/**
	 * @see freenet.oldplugins.plugin.Plugin#startPlugin()
	 */
	public void startPlugin() {
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
	 * @see freenet.oldplugins.plugin.Plugin#stopPlugin()
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

	public void onFetchable(BaseClientPutter state) {
		// Ignore
	}

}
