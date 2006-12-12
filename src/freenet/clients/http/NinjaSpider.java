/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.File;
import java.io.IOException;
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

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Text;

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
import freenet.plugin.HttpPlugin;
import freenet.plugin.PluginManager;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.io.Bucket;

/**
 * NinjaSpider. Produces a ninj^W err ... an XML index.
 *
 *
 * I think regarding the name, a little explanation is required:
 *
 * This name comes from my flatmate, David Anderson. It originated in the following discussion over dinner:
 *   him> I've just thought of something weird...
 *   me> oO
 *   him> The term "spider" for indexing software comes from the analogy "a spider on the web", right ?
 *   me> Yeeess... ?
 *   him> So, if you're writing a spider for a darknet, isn't it a .... *Ninja Spider* ? :D
 *
 * Maybe we should stop buying beers ... However, I needed a name for the spider,
 * and NinjaSpider sounds more fun than XmlSpider, so it stuck :)
 *
 */
public class NinjaSpider implements HttpPlugin, ClientCallback, FoundURICallback {


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

	private static final String indexFilename = "new.index.xml";

	private static final String pluginName = "Ninja spider";

	private static final boolean htmlOnly = true;


	/* The ones below are required to genereate a correct index, see:
	 * http://wiki.freenetproject.org/AnotherFreenetIndexFormat
	 */
	private static final String indexTitle= "This is an index";
	private static final String indexOwner = "Another anonymous";
	private static final String indexOwnerEmail = null; /* can be null */
	private final HashMap sizeOfURIs = new HashMap(); /* String (URI) -> Long */
	private final HashMap mimeOfURIs = new HashMap(); /* String (URI) -> String */
	private final HashMap lastPositionByURI = new HashMap(); /* String (URI) -> Integer */ /* Use to determine word position on each uri */
	private final HashMap positionsByWordByURI = new HashMap(); /* String (URI) -> HashMap (String (word) -> Integer[] (Positions)) */



	private synchronized void queueURI(FreenetURI uri) {
		/* Currently we don't handle PDF or other contents,
		   so it's not interresting to download them
		*/

		String tmp = uri.toString().toLowerCase();
		
		if(htmlOnly
		   && (tmp.indexOf(".htm") < 0)
		   && (tmp.charAt(tmp.length()-1) != '/'))
			return;

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

		sizeOfURIs.put(uri.toString(), new Long(data.size()));
		mimeOfURIs.put(uri.toString(), mimeType);

		try {
			ContentFilter.filter(data, ctx.bucketFactory, mimeType, new URI("http://127.0.0.1:8888/" + uri.toString()), this);
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
			uri = new FreenetURI(baseURI.getPath().substring(1));/*substring(1) because we don't want the initial '/' */
		} catch (MalformedURLException e) {
			Logger.error(this, "Caught " + e, e);
			return;
		}

		if((type != null) && (type.length() != 0) && type.toLowerCase().equals("title")
		   && (s != null) && (s.length() != 0) && (s.indexOf('\n') < 0)) {
			/* We should have a correct title */
			titlesOfURIs.put(uri.toString(), s);
			type = "title";
		}
		else
			type = null;


		String[] words = s.split("[^A-Za-z0-9]");

		Integer lastPosition = null;

		lastPosition = (Integer)lastPositionByURI.get(uri.toString());

		if(lastPosition == null)
			lastPosition = new Integer(1); /* We start to count from 1 */

		for (int i = 0; i < words.length; i++) {
			String word = words[i];
			if ((word == null) || (word.length() == 0))
				continue;
			word = word.toLowerCase();
			
			if(type == null)
				addWord(word, lastPosition.intValue() + i, uri);
			else
				addWord(word, -1 * (i+1), uri);
		}
		
		if(type == null) {
			lastPosition = new Integer(lastPosition.intValue() + words.length);
			lastPositionByURI.put(uri.toString(), lastPosition);
		}
	}

	private synchronized void addWord(String word, int position, FreenetURI uri) {

		/* I know that it's bad for i18n */
		/* But words separation or file filtering seems to already killed words matching [^a-zA-Z] ... */
		if(word.length() < 3)
			return;


		FreenetURI[] uris = (FreenetURI[]) urisByWord.get(word);

		//Integer[] positions = (Integer[]) positionsByWordByURI.get(word);

		urisWithWords.add(uri);


		/* Word position indexation */
		HashMap wordPositionsForOneUri = (HashMap)positionsByWordByURI.get(uri.toString()); /* For a given URI, take as key a word, and gives position */
		
		if(wordPositionsForOneUri == null) {
			wordPositionsForOneUri = new HashMap();
			wordPositionsForOneUri.put(word, new Integer[] { new Integer(position) });
			positionsByWordByURI.put(uri.toString(), wordPositionsForOneUri);
		} else {
			Integer[] positions = (Integer[])wordPositionsForOneUri.get(word);

			if(positions == null) {
				positions = new Integer[] { new Integer(position) };
				wordPositionsForOneUri.put(word, positions);
			} else {
				Integer[] newPositions = new Integer[positions.length + 1];

				System.arraycopy(positions, 0, newPositions, 0, positions.length);
				newPositions[positions.length] = new Integer(position);

				wordPositionsForOneUri.put(word, newPositions);
			}
		}



		/* Words indexation */
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

	/**
	 * Produce an XML index in new.index.xml
	 */
	private synchronized void produceIndex() throws IOException {
		File outputFile;
		StreamResult resultStream;

		if (urisByWord.isEmpty() || urisWithWords.isEmpty()) {
			Logger.normal(this, "No URIs with words -> no index generation");
			return;
		}
		
		outputFile = new File(indexFilename);

		if(outputFile.exists()
		   && !outputFile.canWrite()) {
			Logger.error(this, "Spider: Unable to write '" + indexFilename +"'. Check permissions.");
			return;
		}

		resultStream = new StreamResult(outputFile);


		/* Initialize xml builder */
		Document xmlDoc = null;
		DocumentBuilderFactory xmlFactory = null;
		DocumentBuilder xmlBuilder = null;
		DOMImplementation impl = null;
		Element rootElement = null;

		xmlFactory = DocumentBuilderFactory.newInstance();


		try {
			xmlBuilder = xmlFactory.newDocumentBuilder();
		} catch(javax.xml.parsers.ParserConfigurationException e) {
			/* Will (should ?) never happen */
			Logger.error(this, "Spider: Error while initializing XML generator: "+e.toString());
			return;
		}


		impl = xmlBuilder.getDOMImplementation();

		/* Starting to generate index */

		xmlDoc = impl.createDocument(null, "index", null);
		rootElement = xmlDoc.getDocumentElement();

		/* Adding header to the index */
		Element headerElement = xmlDoc.createElement("header");

		/* -> title */
		Element subHeaderElement = xmlDoc.createElement("title");
		Text subHeaderText = xmlDoc.createTextNode(indexTitle);
		
		subHeaderElement.appendChild(subHeaderText);
		headerElement.appendChild(subHeaderElement);

		/* -> owner */
		subHeaderElement = xmlDoc.createElement("owner");
		subHeaderText = xmlDoc.createTextNode(indexOwner);
		
		subHeaderElement.appendChild(subHeaderText);
		headerElement.appendChild(subHeaderElement);
		
		/* -> owner email */
		if(indexOwnerEmail != null) {
			subHeaderElement = xmlDoc.createElement("email");
			subHeaderText = xmlDoc.createTextNode(indexOwnerEmail);
			
			subHeaderElement.appendChild(subHeaderText);
			headerElement.appendChild(subHeaderElement);
		}



		String[] words = (String[]) urisByWord.keySet().toArray(new String[urisByWord.size()]);
		Arrays.sort(words);

		FreenetURI[] uris = (FreenetURI[]) urisWithWords.toArray(new FreenetURI[urisWithWords.size()]);
		HashMap urisToNumbers = new HashMap();

		/* Adding freesite list to the index */
		Element filesElement = xmlDoc.createElement("files"); /* filesElement != fileElement */

		for (int i = 0; i < uris.length; i++) {
			urisToNumbers.put(uris[i], new Integer(i));
			
			Element fileElement = xmlDoc.createElement("file");

			fileElement.setAttribute("id", Integer.toString(i));
			fileElement.setAttribute("key", uris[i].toString());
			
			Long size = (Long)sizeOfURIs.get(uris[i].toString());

			if(size == null) {
				Logger.error(this, "Spider: size is missing");
			} else {
				fileElement.setAttribute("size", size.toString());
			}
			fileElement.setAttribute("mime", ((String)mimeOfURIs.get(uris[i].toString())));

			Element titleElement = xmlDoc.createElement("option");
			titleElement.setAttribute("name", "title");
			titleElement.setAttribute("value", (String)titlesOfURIs.get(uris[i].toString()));

			fileElement.appendChild(titleElement);
			filesElement.appendChild(fileElement);
		}


		/* Adding word index */
		Element keywordsElement = xmlDoc.createElement("keywords");
		
		/* Word by word */
		for (int i = 0; i < words.length; i++) {
			Element wordElement = xmlDoc.createElement("word");
			wordElement.setAttribute("v", words[i]);

			FreenetURI[] urisForWord = (FreenetURI[]) urisByWord.get(words[i]);

			/* URI by URI */
			for (int j = 0; j < urisForWord.length; j++) {
				FreenetURI uri = urisForWord[j];
				Integer x = (Integer) urisToNumbers.get(uri);
				
				if (x == null) {
					Logger.error(this, "Eh?");
					continue;
				}

				Element uriElement = xmlDoc.createElement("file");
				uriElement.setAttribute("id", x.toString());

				/* Position by position */
				HashMap positionsForGivenWord = (HashMap)positionsByWordByURI.get(uri.toString());
				Integer[] positions = (Integer[])positionsForGivenWord.get(words[i]);

				StringBuffer positionList = new StringBuffer();

				for(int k=0; k < positions.length ; k++) {
					if(k!=0)
						positionList.append(',');

					positionList.append(positions[k].toString());
				}
				
				uriElement.appendChild(xmlDoc.createTextNode(positionList.toString()));

				wordElement.appendChild(uriElement);
			}

			keywordsElement.appendChild(wordElement);
		}

		rootElement.appendChild(headerElement);
		rootElement.appendChild(filesElement);
		rootElement.appendChild(keywordsElement);

		/* Serialization */
		DOMSource domSource = new DOMSource(xmlDoc);
		TransformerFactory transformFactory = TransformerFactory.newInstance();
		Transformer serializer;

		try {
			serializer = transformFactory.newTransformer();
		} catch(javax.xml.transform.TransformerConfigurationException e) {
			Logger.error(this, "Spider: Error while serializing XML (transformFactory.newTransformer()): "+e.toString());
			return;
		}


		serializer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
		serializer.setOutputProperty(OutputKeys.INDENT,"yes");
		
		/* final step */
		try {
			serializer.transform(domSource, resultStream);
		} catch(javax.xml.transform.TransformerException e) {
			Logger.error(this, "Spider: Error while serializing XML (transform()): "+e.toString());
			return;
		}

		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Spider: indexes regenerated.");
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

			HTMLNode pageNode = pageMaker.getPageNode(pluginName);
			HTMLNode contentNode = pageMaker.getContentNode(pageNode);

			/* create copies for multi-threaded use */
			if (listName == null) {
				Map runningFetches = new HashMap(runningFetchesByURI);
				List queued = new ArrayList(queuedURIList);
				Set visited = new HashSet(visitedURIs);
				Set failed = new HashSet(failedURIs);
				contentNode.addChild(createNavbar(runningFetches.size(), queued.size(), visited.size(), failed.size()));
				contentNode.addChild(createAddBox());
				contentNode.addChildren(createList("Running Fetches", "running", runningFetches.keySet(), maxShownURIs));
				contentNode.addChildren(createList("Queued URIs", "queued", queued, maxShownURIs));
				contentNode.addChildren(createList("Visited URIs", "visited", visited, maxShownURIs));
				contentNode.addChildren(createList("Failed URIs", "failed", failed, maxShownURIs));
			} else {
				contentNode.addChild(createBackBox());
				if ("failed".equals(listName)) {
					Set failed = new HashSet(failedURIs);
					contentNode.addChildren(createList("Failed URIs", "failed", failed, -1));	
				} else if ("visited".equals(listName)) {
					Set visited = new HashSet(visitedURIs);
					contentNode.addChildren(createList("Visited URIs", "visited", visited, -1));
				} else if ("queued".equals(listName)) {
					List queued = new ArrayList(queuedURIList);
					contentNode.addChildren(createList("Queued URIs", "queued", queued, -1));
				} else if ("running".equals(listName)) {
					Map runningFetches = new HashMap(runningFetchesByURI);
					contentNode.addChildren(createList("Running Fetches", "running", runningFetches.keySet(), -1));
				}
			}
			MultiValueTable responseHeaders = new MultiValueTable();
			StringBuffer pageBuffer = new StringBuffer();
			pageNode.generate(pageBuffer);
			byte[] responseBytes = pageBuffer.toString().getBytes();
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
				sendSimpleResponse(context, "URL invalid", "The given URI is not valid. Please return and try again.");
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
		HTMLNode pageNode = pageMaker.getPageNode(title);
		HTMLNode contentNode = pageMaker.getContentNode(pageNode);
		HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-alert");
		infobox.addChild("div", "class", "infobox-header", title);
		infobox.addChild("div", "class", "infobox-content", message);
		StringBuffer pageBuffer = new StringBuffer();
		pageNode.generate(pageBuffer);
		byte[] responseBytes = pageBuffer.toString().getBytes("utf-8");
		context.sendReplyHeaders(200, "OK", new MultiValueTable(), "text/html; charset=utf-8", responseBytes.length);
		context.writeData(responseBytes);
	}
	
	private HTMLNode createBackBox() {
		HTMLNode backBox = new HTMLNode("div", "class", "infobox");
		HTMLNode backBoxContent = backBox.addChild("div", "class", "infobox-content");
		backBoxContent.addChild("#", "Return to the ");
		backBoxContent.addChild("a", "href", "?action=list", "list of all URIs");
		backBoxContent.addChild("#", ".");
		return backBox;
	}
	
	private HTMLNode createAddBox() {
		HTMLNode addBox = new HTMLNode("div", "class", "infobox");
		addBox.addChild("div", "class", "infobox-header", "Add a URI");
		HTMLNode addForm = addBox.addChild("div", "class", "infobox-content").addChild("form", new String[] { "action", "method" }, new String[] { "", "get" });
		addForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "action", "add" });
		addForm.addChild("input", new String[] { "type", "size", "name", "value" }, new String[] { "text", "40", "key", "" });
		addForm.addChild("input", new String[] { "type", "value" }, new String[] { "submit", "Add URI" });
		return addBox;
	}

	private HTMLNode createNavbar(int running, int queued, int visited, int failed) {
		HTMLNode infobox = new HTMLNode("div", "class", "infobox navbar");
		infobox.addChild("div", "class", "infobox-header", "Page navigation");
		HTMLNode links = infobox.addChild("div", "class", "infobox-content").addChild("ul");
		links.addChild("li").addChild("a", "href", "#running", "Running (" + running + ')');
		links.addChild("li").addChild("a", "href", "#queued", "Queued (" + queued + ')');
		links.addChild("li").addChild("a", "href", "#visited", "Visited (" + visited + ')');
		links.addChild("li").addChild("a", "href", "#failed", "Failed (" + failed + ')');
		return infobox;
	}

	private HTMLNode[] createList(String listName, String anchorName, Collection collection, int maxCount) {
		HTMLNode listBox = new HTMLNode("div", "class", "infobox");
		listBox.addChild("div", "class", "infobox-header", listName + " (" + collection.size() + ')');
		HTMLNode listContent = listBox.addChild("div", "class", "infobox-content");
		Iterator collectionItems = collection.iterator();
		int itemCount = 0;
		while (collectionItems.hasNext()) {
			FreenetURI uri = (FreenetURI) collectionItems.next();
			listContent.addChild(uri.toString());
			listContent.addChild("br");
			if (itemCount++ == maxCount) {
				listContent.addChild("br");
				listContent.addChild("a", "href", "?action=list&listName=" + anchorName, "Show all\u2026");
				break;
			}
		}
		return new HTMLNode[] { new HTMLNode("a", "name", anchorName), listBox };
	}

	/**
	 * @see freenet.plugin.Plugin#getPluginName()
	 */
	public String getPluginName() {
		return pluginName;
	}

	/**
	 * @see freenet.plugin.Plugin#setPluginManager(freenet.plugin.PluginManager)
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
	 * @see freenet.plugin.Plugin#startPlugin()
	 */
	public void startPlugin() {
		FreenetURI[] initialURIs = core.bookmarkManager.getBookmarkURIs();
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

	public void onFetchable(BaseClientPutter state) {
		// Ignore
	}



}
