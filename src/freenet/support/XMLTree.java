package freenet.support;
import java.io.IOException;
import java.io.InputStream;
import java.util.EmptyStackException;
import java.util.Set;
import java.util.Stack;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.ext.DefaultHandler2;


/**
 * A class which parses an input stream as XML and builds a tree structure of the XML.
 * Use getRoot() to access the tree.
 * 
 * FIXME: Also use this class in the WoT plugin XML parsers.
 */
public class XMLTree extends DefaultHandler2 {
	private final Set<String> mAllowedElementNames;

	public XMLTree(Set<String> allowedElementNames, InputStream inputStream) throws SAXException, IOException, ParserConfigurationException {
		mAllowedElementNames = allowedElementNames;
		SAXParserFactory.newInstance().newSAXParser().parse(inputStream, this);
	}

	public XMLElement getRoot() {
		return rootElement;
	}

	public class XMLElement {
		public final String name;
		public final Attributes attrs;
		public String cdata = null;
		public MultiValueTable<String, XMLElement> children = new MultiValueTable<String, XMLElement>();

		public XMLElement(String newName, Attributes newAttributes) throws Exception {
			name = newName;
			attrs = newAttributes;

			if(!mAllowedElementNames.contains(name))
				throw new Exception("Unknown element in Message: " + name);
		}
	}

	private XMLElement rootElement = null;

	private final Stack<XMLElement> elements = new Stack<XMLElement>();

	@Override
	public void startElement(String nameSpaceURI, String localName, String qName, Attributes attrs) throws SAXException {
		String name = (qName == null ? localName : qName);

		try {
			XMLElement element = new XMLElement(name, attrs);

			if(rootElement == null)
				rootElement = element;

			try {
				elements.peek().children.put(name, element);
			}
			catch(EmptyStackException e) { }
			elements.push(element);

			/* FIXME: A speedup would be to only store CDATA for elements selected by the creator of the XMLTreeGenerator. */
			/* Alternatively the <Date> and <Time> elements could be changed to also contain a CDATA tag, then the following
			 * line could be removed because all CDATA in the message XML is also labeled as CDATA. We should ask SomeDude
			 * whether FMS can be modified to also do that, we want to stay compatible to FMS message XML. */
			cdata = new StringBuffer(10 * 1024);
		} catch (Exception e) {
			Logger.error(this, "Parsing error", e);
		}
	}

	StringBuffer cdata;

	public void beginCDATA() {
		cdata = new StringBuffer(10 * 1024);
	}

	public void characters(char[] ch, int start, int length) {
		if(cdata != null)
			cdata.append(ch, start, length);
	}

	public void endCDATA() {
		elements.peek().cdata = cdata.toString();
		cdata = null;
	}

	@Override
	public void endElement(String uri, String localName, String qName) {
		try {
			XMLElement newElement = elements.pop(); /* This can fail because we do not push elements with invalid name */
			if(cdata != null)
				newElement.cdata = cdata.toString();
		}
		catch(EmptyStackException e) {}
		cdata = null;
	}
}