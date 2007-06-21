/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

import org.w3c.dom.Attr;
import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;

import freenet.client.ClientMetadata;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InsertException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientCallback;
import freenet.client.async.ClientGetter;
import freenet.client.async.USKCallback;
import freenet.clients.http.filter.ContentFilter;
import freenet.clients.http.filter.FoundURICallback;
import freenet.clients.http.filter.UnsafeContentTypeException;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.node.NodeClientCore;
import freenet.node.RequestStarter;
import freenet.oldplugins.plugin.HttpPlugin;
import freenet.oldplugins.plugin.PluginManager;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.api.Bucket;
import freenet.support.api.HTTPRequest;

/**
 * Spider. Produces an index.
 */
public class XMLSpider implements HttpPlugin, ClientCallback, FoundURICallback ,USKCallback{

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
	//private static final String indexFilename = "index.xml";
	private static final String DEFAULT_INDEX_DIR = "myindex/";
	public Set allowedMIMETypes;
	private static final int MAX_ENTRIES = 50;
	private static final String pluginName = "XML spider";
	
	private static final String indexTitle= "This is an index";
	private static final String indexOwner = "Another anonymous";
	private static final String indexOwnerEmail = null;
	private final HashMap sizeOfURIs = new HashMap(); /* String (URI) -> Long */
	private final HashMap mimeOfURIs = new HashMap(); /* String (URI) -> String */
	private final HashMap lastPositionByURI = new HashMap(); /* String (URI) -> Integer */ /* Use to determine word position on each uri */
	private final HashMap positionsByWordByURI = new HashMap(); /* String (URI) -> HashMap (String (word) -> Integer[] (Positions)) */

	// Can have many; this limit only exists to save memory.
	private static final int maxParallelRequests = 20;
	private int maxShownURIs = 50;
	private HashMap urisToNumbers;
	private NodeClientCore core;
	private FetchContext ctx;
	private final short PRIORITY_CLASS = RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS;
	private boolean stopped = true;
	PluginRespirator pr;
	

	private synchronized void queueURI(FreenetURI uri) {
		//not adding the html condition
		if ((!visitedURIs.contains(uri)) && queuedURISet.add(uri)) {
			queuedURIList.addLast(uri);
			visitedURIs.add(uri);
		}
	}

	private void startSomeRequests() {

		
		FreenetURI[] initialURIs = core.bookmarkManager.getBookmarkURIs();
		for (int i = 0; i < initialURIs.length; i++)
		{
		queueURI(initialURIs[i]);
		}
					
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
				if((uri.getKeyType()).equals("USK")){
				if(uri.getSuggestedEdition() < 0)
					uri = uri.setSuggestedEdition((-1)* uri.getSuggestedEdition());
				try{
					(ctx.uskManager).subscribe(USK.create(uri),this, false, this);	
				}catch(Exception e){
					
				}
				
				}
				ClientGetter getter = makeGetter(uri);
				toStart.add(getter);
				}
		}
			for (int i = 0; i < toStart.size(); i++) {
				
			ClientGetter g = (ClientGetter) toStart.get(i);
			try {
				runningFetchesByURI.put(g.getURI(), g);
				g.start();
				FileWriter outp = new FileWriter("logfile2",true);
				outp.write("URI "+g.getURI().toString()+'\n');
				
				outp.close();
				} catch (FetchException e) {
					onFailure(e, g);
				}
				catch (IOException e){
					Logger.error(this, "the logfile can not be written"+e.toString(), e);
				}
		
			}
		//}
				
	}
	

	private ClientGetter makeGetter(FreenetURI uri) {
		ClientGetter g = new ClientGetter(this, core.requestStarters.chkFetchScheduler, core.requestStarters.sskFetchScheduler, uri, ctx, PRIORITY_CLASS, this, null, null);
		return g;
	}

	public void onSuccess(FetchResult result, ClientGetter state) {
		FreenetURI uri = state.getURI();
		try{
	    FileWriter output = new FileWriter("logfile",true);
	    output.write(uri.toString()+"\n");
	    output.close();
		}
		catch(Exception e){
			Logger.error(this, "The uri could not be removed from running "+e.toString(), e);
		}
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
		try{
			FileWriter outp = new FileWriter("failed",true);
			outp.write("failed "+e.toString()+" for "+uri+'\n');
			outp.close();
			
		}catch(Exception e2){
			
		}
		synchronized (this) {
			runningFetchesByURI.remove(uri);
			failedURIs.add(uri);
		}
		if (e.newURI != null)
			queueURI(e.newURI);
//		else
//			queueURI(uri);
		startSomeRequests();
		
		
	}

	public void onSuccess(BaseClientPutter state) {
		// Ignore
	}

	public void onFailure(InsertException e, BaseClientPutter state) {
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
			uri = new FreenetURI(baseURI.getPath().substring(1));
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
		else type = null;


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
			try{
			if(type == null)
				addWord(word, lastPosition.intValue() + i, uri);
			else
				addWord(word, -1 * (i+1), uri);
			}
			catch (Exception e){}
		}
		
		if(type == null) {
			lastPosition = new Integer(lastPosition.intValue() + words.length);
			lastPositionByURI.put(uri.toString(), lastPosition);
		}
		
	}

	private synchronized void addWord(String word, int position,FreenetURI uri) throws Exception{
		
		
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
				generateIndex();
			} catch (IOException e) {
				Logger.error(this, "Caught " + e + " while creating index", e);
			}
			tProducedIndex = System.currentTimeMillis();
		}
		
	}

	private synchronized void produceIndex() throws IOException,NoSuchAlgorithmException {
		// Produce the main index file.
		
		//the number of bits to consider for matching 
		int prefix = 1 ;
	
		if (urisByWord.isEmpty() || urisWithWords.isEmpty()) {
			System.out.println("No URIs with words");
			return;
		}
		File outputFile = new File(DEFAULT_INDEX_DIR+"index.xml");
		StreamResult resultStream;
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
		xmlDoc = impl.createDocument(null, "main_index", null);
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
		urisToNumbers = new HashMap();
		Element prefixElement = xmlDoc.createElement("prefix");
		prefixElement.setAttribute("value", prefix+"");
	

		for (int i = 0; i < uris.length; i++) {
			urisToNumbers.put(uris[i], new Integer(i));
			}
		
		//all index files are ready
		/* Adding word index */
		Element keywordsElement = xmlDoc.createElement("keywords");
		for(int i = 0;i<16;i++){
			generateSubIndex(DEFAULT_INDEX_DIR+"index_"+Integer.toHexString(i)+".xml");
			Element subIndexElement = xmlDoc.createElement("subIndex");
			if(i<=9)
			subIndexElement.setAttribute("key",i+"");
			else
				subIndexElement.setAttribute("key",Integer.toHexString(i));
			//the subindex element key will contain the bits used for matching in that subindex
			keywordsElement.appendChild(subIndexElement);
		}
		

		// make sure that prefix is the first child of root Element
		rootElement.appendChild(prefixElement);
		rootElement.appendChild(headerElement);
		
		//rootElement.appendChild(filesElement);
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
	
	//the main xml file is generated 
	//now as each word is generated enter it into the respective subindex
	//now the parsing will start and nodes will be added as needed 
		

	}

	private synchronized void generateIndex() throws Exception{
		String[] words = (String[]) urisByWord.keySet().toArray(new String[urisByWord.size()]);
		Arrays.sort(words);
		for (int i = 0; i < words.length; i++) {
		try{
		
		String prefix_match = getIndex(words[i]);

		boolean addedWord = addWord(prefix_match,words[i]);

		if(addedWord == false)
			{
			split(prefix_match);
			regenerateIndex(prefix_match);
			prefix_match = getIndex(words[i]);
			addWord(prefix_match,words[i]);
			}
		}
		catch(Exception e2){Logger.error(this,"The Word could not be added"+ e2.toString(), e2); }
		}	

	
	}
	private void regenerateIndex(String prefix) throws Exception{
		//redistribute the entries in prefix.xml to prefix(0-f).xml
		DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
		Document doc = docBuilder.parse(DEFAULT_INDEX_DIR+"index_"+prefix+".xml");
		Element root = doc.getDocumentElement();
		NodeList wordList = root.getElementsByTagName("word");
		for(int i = 0;i<wordList.getLength();i++){
			Element word = (Element)wordList.item(i);
			String value = word.getAttribute("v");
			String prefix_match = getIndex(value);
			addWord(prefix_match,value);
		}
	}
	
	private String getIndex(String word) throws Exception {
		DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
		Document doc = docBuilder.parse(DEFAULT_INDEX_DIR+"index.xml");
		Element root = doc.getDocumentElement();
		Attr prefix_value = (Attr) (root.getElementsByTagName("prefix").item(0)).getAttributes().getNamedItem("value");
		int prefix = Integer.parseInt(prefix_value.getValue()); 
		String md5 = MD5(word);
		NodeList subindexList = root.getElementsByTagName("subIndex");
		String str = md5.substring(0,prefix);		
		String prefix_match = search(str,subindexList);
		
		return prefix_match;
	}
	
	private boolean addWord(String prefix, String str) throws Exception
	{
		//this word has to be added to the particular subindex
		// modify the corresponding index
		try{
			DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
			DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
			Document doc = docBuilder.parse(DEFAULT_INDEX_DIR+"index_"+prefix+".xml");
			Element root = doc.getDocumentElement();
			
			Element entry = (Element) root.getElementsByTagName("entries").item(0);
			
			Attr no_entries = (Attr) entry.getAttributes().getNamedItem("value");
			
			Element filesElement = (Element) root.getElementsByTagName("files").item(0);
			NodeList filesList = filesElement.getElementsByTagName("file");
			if(Integer.parseInt(no_entries.getValue()) >= MAX_ENTRIES) return false;
			else
			{
			//increment the number of entries
			entry.setAttribute("value",(Integer.parseInt(no_entries.getValue())+1)+"");
			//add the entry
			
			Element wordElement = doc.createElement("word");
			wordElement.setAttribute("v", str);

			FreenetURI[] urisForWord = (FreenetURI[]) urisByWord.get(str);

			/* URI by URI */
			for (int j = 0; j < urisForWord.length; j++) {
				FreenetURI uri = urisForWord[j];
				Integer x = (Integer) urisToNumbers.get(uri);
				
				if (x == null) {
					Logger.error(this, "Eh?");
					continue;
				}

				Element uriElement = doc.createElement("file");
				Element fileElement = doc.createElement("file");
				uriElement.setAttribute("id", x.toString());
				fileElement.setAttribute("id", x.toString());
				fileElement.setAttribute("key", uri.toString());
//				/* Position by position */
				HashMap positionsForGivenWord = (HashMap)positionsByWordByURI.get(uri.toString());
				Integer[] positions = (Integer[])positionsForGivenWord.get(str);

				StringBuffer positionList = new StringBuffer();

				for(int k=0; k < positions.length ; k++) {
					if(k!=0)
						positionList.append(',');

					positionList.append(positions[k].toString());
				}
				
				uriElement.appendChild(doc.createTextNode(positionList.toString()));
				int l;
			for(l = 0;l<filesList.getLength();l++)
				{ Element file = (Element) filesList.item(l);
				if(file.getAttribute("id").equals(x.toString()))
				
				break;
				}
				wordElement.appendChild(uriElement);
				if(l>=filesList.getLength())
				filesElement.appendChild(fileElement);
			}
			Element keywordsElement = (Element) root.getElementsByTagName("keywords").item(0);
			keywordsElement.appendChild(wordElement);
		
			
			
			DOMSource domSource = new DOMSource(doc);
			TransformerFactory transformFactory = TransformerFactory.newInstance();
			Transformer serializer;

			
				serializer = transformFactory.newTransformer();
			
				
						
			File outputFile = new File(DEFAULT_INDEX_DIR+"index_"+prefix+".xml");
			StreamResult resultStream;
			resultStream = new StreamResult(outputFile);

			serializer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
			serializer.setOutputProperty(OutputKeys.INDENT,"yes");
			
			/* final step */
			try {
				serializer.transform(domSource, resultStream);
			} catch(javax.xml.transform.TransformerException e) {}
			}
			
			return true;	
		}
		
		catch(Exception e){Logger.error(this,"Word could not be added to the subindex"+ e.toString(), e);}
		return false;
	}
	private void split(String prefix) throws Exception
	{
		//first we need to split the current subindex into 16 newones
		//then read from the original one and append to the new ones
		// make the entry in the main index..
		DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
		Document doc = docBuilder.parse(DEFAULT_INDEX_DIR+"index.xml");
		Element root = doc.getDocumentElement();
		Element prefixElt =(Element) root.getElementsByTagName("prefix").item(0);
		int prefix_current = Integer.parseInt(prefixElt.getAttribute("value"));
		if (prefix_current <= prefix.length())
		prefixElt.setAttribute("value", (prefix_current+1)+"");
		
		Element keywordElement = (Element) root.getElementsByTagName("keywords").item(0);
		
		NodeList subIndexElt = root.getElementsByTagName("subIndex");
		for(int i =0;i<subIndexElt.getLength();i++)
		{
			Element subIndex = (Element) subIndexElt.item(i);
			if((subIndex.getAttribute("key")).equals(prefix)) {
				keywordElement.removeChild(subIndex);
				break;
			}
		}
		
		for(int i = 0;i<16;i++)
			{
			Element subIndex = doc.createElement("subIndex");
			generateSubIndex(DEFAULT_INDEX_DIR+"index_"+prefix+Integer.toHexString(i)+".xml");
			subIndex.setAttribute("key",prefix.concat(Integer.toHexString(i)));
			keywordElement.appendChild(subIndex);
			}
		
		
		DOMSource domSource = new DOMSource(doc);
		TransformerFactory transformFactory = TransformerFactory.newInstance();
		Transformer serializer;
		serializer = transformFactory.newTransformer();
		File outputFile = new File(DEFAULT_INDEX_DIR+"index.xml");
		StreamResult resultStream;
		resultStream = new StreamResult(outputFile);

		serializer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
		serializer.setOutputProperty(OutputKeys.INDENT,"yes");
		
		/* final step */
		try {
			serializer.transform(domSource, resultStream);
		} catch(javax.xml.transform.TransformerException e) {}
	}
	
	public String search(String str,NodeList list) throws Exception
	{
		int prefix = str.length();
		for(int i = 0;i<list.getLength();i++){
			Element subIndex = (Element) list.item(i);
			String key = subIndex.getAttribute("key");
			if(key.equals(str)) return key;
		}
		return search(str.substring(0, prefix-1),list);
	}

	
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
			HTMLNode pageNode = pageMaker.getPageNode("The XML Spider", context);
			HTMLNode contentNode = pageMaker.getContentNode(pageNode);
			/* create copies for multi-threaded use */
			if (listName == null) {
				Map runningFetches = new HashMap(runningFetchesByURI);
				List queued = new ArrayList(queuedURIList);
				Set visited = new HashSet(visitedURIs);
				Set failed = new HashSet(failedURIs);
				contentNode.addChild(createNavbar(pageMaker, runningFetches.size(), queued.size(), visited.size(), failed.size()));
				contentNode.addChild(createAddBox(pageMaker, context));
				contentNode.addChild(createList(pageMaker, "Running FetcheIIIs", "running", runningFetches.keySet(), maxShownURIs));
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
		HTMLNode pageNode = pageMaker.getPageNode(title, context);
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
		return pluginName;
	}

	/**
	 * @see freenet.oldplugins.plugin.Plugin#setPluginManager(freenet.oldplugins.plugin.PluginManager)
	 */
	public void setPluginManager(PluginManager pluginManager) {
		
		this.core = pluginManager.getClientCore();
		this.ctx = core.makeClient((short) 0).getFetchContext();
		ctx.maxSplitfileBlockRetries = 10;
		ctx.maxNonSplitfileRetries = 10;
		ctx.maxTempLength = 2 * 1024 * 1024;
		ctx.maxOutputLength = 2 * 1024 * 1024;
		allowedMIMETypes = new HashSet();
		allowedMIMETypes.add(new String("text/html"));
		allowedMIMETypes.add(new String("text/plain"));
		allowedMIMETypes.add(new String("application/xhtml+xml"));
	//	allowedMIMETypes.add(new String("application/zip"));
		ctx.allowedMIMETypes = new HashSet(allowedMIMETypes);
	//	ctx.allowedMIMETypes.add("text/html"); 
		tProducedIndex = System.currentTimeMillis();
	}


	/**
	 * @see freenet.oldplugins.plugin.Plugin#startPlugin()
	 */
	public void startPlugin() {
		stopped = false;
		
		Thread starterThread = new Thread("Spider Plugin Starter") {
			public void run() {
				try{
					Thread.sleep(30 * 1000); // Let the node start up
				} catch (InterruptedException e){}
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
	private static String convertToHex(byte[] data) {
        StringBuffer buf = new StringBuffer();
        for (int i = 0; i < data.length; i++) {
        	int halfbyte = (data[i] >>> 4) & 0x0F;
        	int two_halfs = 0;
        	do {
	        	if ((0 <= halfbyte) && (halfbyte <= 9))
	                buf.append((char) ('0' + halfbyte));
	            else
	            	buf.append((char) ('a' + (halfbyte - 10)));
	        	halfbyte = data[i] & 0x0F;
        	} while(two_halfs++ < 1);
        }
        return buf.toString();
    }
	//this function will return the String representation of the MD5 hash for the input string 
	public static String MD5(String text) throws NoSuchAlgorithmException, UnsupportedEncodingException  {
		MessageDigest md;
		md = MessageDigest.getInstance("MD5");
		byte[] md5hash = new byte[32];
		md.update(text.getBytes("iso-8859-1"), 0, text.length());
		md5hash = md.digest();
		return convertToHex(md5hash);
	}
	
	public void generateSubIndex(String filename){
//generates the new subIndex
		File outputFile = new File(filename);
		StreamResult resultStream;
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

		xmlDoc = impl.createDocument(null, "sub_index", null);
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

		
		Element filesElement = xmlDoc.createElement("files"); /* filesElement != fileElement */

		Element EntriesElement = xmlDoc.createElement("entries");
		EntriesElement.setNodeValue("0");
		EntriesElement.setAttribute("value", "0");
		//all index files are ready
		/* Adding word index */
		Element keywordsElement = xmlDoc.createElement("keywords");
		
		rootElement.appendChild(EntriesElement);
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
	
public void terminate(){
	synchronized (this) {
		stopped = true;
		queuedURIList.clear();
	}
}
	
public void runPlugin(PluginRespirator pr){
	this.pr = pr;
	this.core = pr.getNode().clientCore;
	this.ctx = core.makeClient((short) 0).getFetchContext();
	ctx.maxSplitfileBlockRetries = 10;
	ctx.maxNonSplitfileRetries = 10;
	ctx.maxTempLength = 2 * 1024 * 1024;
	ctx.maxOutputLength = 2 * 1024 * 1024;
	allowedMIMETypes = new HashSet();
	allowedMIMETypes.add(new String("text/html"));
	allowedMIMETypes.add(new String("text/plain"));
	allowedMIMETypes.add(new String("application/xhtml+xml"));
//	allowedMIMETypes.add(new String("application/zip"));
	ctx.allowedMIMETypes = new HashSet(allowedMIMETypes);
//	ctx.allowedMIMETypes.add("text/html"); 
	tProducedIndex = System.currentTimeMillis();
	
	stopped = false;
	
	Thread starterThread = new Thread("Spider Plugin Starter") {
		public void run() {
			try{
				Thread.sleep(30 * 1000); // Let the node start up
			} catch (InterruptedException e){}
			startSomeRequests();
		}
	};
	starterThread.setDaemon(true);
	starterThread.start();
}


public void onFoundEdition(long l, USK key){
	FreenetURI uri = key.getURI();
	if(runningFetchesByURI.containsKey(uri)) runningFetchesByURI.remove(uri);
	uri = key.getURI().setSuggestedEdition(l);
	queueURI(uri);
}
	
	
}
