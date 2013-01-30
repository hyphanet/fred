package freenet.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class HTMLNode implements XMLCharacterClasses, Cloneable {
	
	private static final Pattern namePattern = Pattern.compile("^[" + NAME + "]*$");
	private static final Pattern simpleNamePattern = Pattern.compile("^[A-Za-z][A-Za-z0-9]*$");
	public static HTMLNode STRONG = new HTMLNode("strong").setReadOnly();

	protected final String name;
	
	private boolean readOnly;
	
	public HTMLNode setReadOnly() {
		readOnly = true;
		return this;
	}

	/** Text to be inserted between tags, or possibly raw HTML. Only non-null if name
	 * is "#" (= text) or "%" (= raw HTML). Otherwise the constructor will allocate a
	 * separate child node to contain it. */
	private String content;

	private final Map<String, String> attributes = new HashMap<String, String>();

	protected final List<HTMLNode> children = new ArrayList<HTMLNode>();

	public HTMLNode(String name) {
		this(name, null);
	}

	private static final ArrayList<String> EmptyTag = new ArrayList<String>(10);
	private static final ArrayList<String> OpenTags = new ArrayList<String>(12);
	private static final ArrayList<String> CloseTags = new ArrayList<String>(12);

	static {
		/* HTML elements which are allowed to be empty */
		EmptyTag.add("area");
		EmptyTag.add("base");
		EmptyTag.add("br");
		EmptyTag.add("col");
		EmptyTag.add("hr");
		EmptyTag.add("img");
		EmptyTag.add("input");
		EmptyTag.add("link");
		EmptyTag.add("meta");
		EmptyTag.add("param");
		/* HTML elements for which we should add a newline following the open tag. */
		OpenTags.add("body");
		OpenTags.add("div");
		OpenTags.add("form");
		OpenTags.add("head");
		OpenTags.add("html");
		OpenTags.add("input");
		OpenTags.add("ol");
		OpenTags.add("script");
		OpenTags.add("table");
		OpenTags.add("td");
		OpenTags.add("tr");
		OpenTags.add("ul");
		/* HTML elements for which we should add a newline following the close tag. */
		CloseTags.add("h1");
		CloseTags.add("h2");
		CloseTags.add("h3");
		CloseTags.add("h4");
		CloseTags.add("h5");
		CloseTags.add("h6");
		CloseTags.add("li");
		CloseTags.add("link");
		CloseTags.add("meta");
		CloseTags.add("noscript");
		CloseTags.add("option");
		CloseTags.add("title");
	}

	/** Tests an HTML element name to determine if it is one of the elements permitted
	 * to be empty in the XHTML spec ( http://www.w3.org/TR/xhtml1/ )
	 * @param name The name of the html element
	 * @return True if the element is allowed to be empty
	 */
	private Boolean isEmptyElement(String name) {
		return EmptyTag.contains(name);
	}

	/** Tests an HTML element to determine if we should add a newline after the opening tag
	 * for readability
	 * @param name The name of the html element
	 * @return True if we should add a newline after the opening tag
	 */
	Boolean newlineOpen(String name) {
		return OpenTags.contains(name);
	}

	/** Tests an HTML element to determine if we should add a newline after the closing tag
	* for readability. All tags with newlines after the opening tag also get newlines after
	* the closing tag.
	* @param name The name of the html element
	* @return True if we should add a newline after the opening tag
	*/
	private Boolean newlineClose(String name) {
		return (newlineOpen(name) || CloseTags.contains(name));
	}

	/** Returns a properly formatted closing angle bracket to complete an open tag of a
	 * named html element
	 * @param name the name of the element
	 * @return the proper string of characters to complete the open tag
	 */
	private String OpenSuffix(String name) {
		if (isEmptyElement(name)) {
			return " />";
		} else {
			return ">";
		}
	}

	/** Returns a closing tag for a named html elemen
	 * @param name the name of the element
	 * @return the complete closing tag for the element
	 */
	private String CloseTag(String name) {
		if (isEmptyElement(name)) {
			return "";
		} else {
			return "</" + name + ">";
		}
	}

	private String indentString(int indentDepth) {
		StringBuffer indentLine = new StringBuffer();

		for (int indentIndex = 0, indentCount = indentDepth+1; indentIndex < indentCount; indentIndex++) {
			indentLine.append('\t');
		}
		return indentLine.toString();
	}

	public HTMLNode(String name, String content) {
		this(name, (String[]) null, (String[]) null, content);
	}

	public HTMLNode(String name, String attributeName, String attributeValue) {
		this(name, attributeName, attributeValue, null);
	}

	public HTMLNode(String name, String attributeName, String attributeValue, String content) {
		this(name, new String[] { attributeName }, new String[] { attributeValue }, content);
	}

	public HTMLNode(String name, String[] attributeNames, String[] attributeValues) {
		this(name, attributeNames, attributeValues, null);
	}

	protected HTMLNode(HTMLNode node, boolean clearReadOnly) {
		attributes.putAll(node.attributes);
		children.addAll(node.children);
		content = node.content;
		name = node.name;
		if(clearReadOnly)
			readOnly = false;
		else
			readOnly = node.readOnly;
	}
	
	@Override
	public HTMLNode clone() {
		// Implement Cloneable to shut up findbugs. We need a deep copy.
		// FIXME is clearing read only an abuse of the clone() API? Should we rename the method?
		return new HTMLNode(this, true);
	}
	
	protected boolean checkNamePattern(String str) {
		// Workaround buggy java regexes, also probably slightly faster.
		if(str.length() < 1) return false;
		char c;
		c = str.charAt(0);
		if((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
			boolean simpleMatch = true;
			for(int i=1;i<str.length();i++) {
				c = str.charAt(i);
				if(!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
					simpleMatch = false;
					break;
				}
			}
			if(simpleMatch) return true;
		}
		// Regex-based match. Probably more expensive, and problems (infinite recursion in Pattern$6.isSatisfiedBy) have been seen in practice.
		// Oddly these problems were seen where the answer is almost certainly in the first matcher, because the tag name was "html"...
		return simpleNamePattern.matcher(str).matches() || 
			namePattern.matcher(str).matches();
	}
	
	public HTMLNode(String name, String[] attributeNames, String[] attributeValues, String content) {
		if ((name == null) || (!"#".equals(name) && !"%".equals(name) && !checkNamePattern(name))) {
			throw new IllegalArgumentException("element name is not legal");
		}
		if ((attributeNames != null) && (attributeValues != null)) {
			if (attributeNames.length != attributeValues.length) {
				throw new IllegalArgumentException("attribute names and values differ in length");
			}
			for (int attributeIndex = 0, attributeCount = attributeNames.length; attributeIndex < attributeCount; attributeIndex++) {
				if ((attributeNames[attributeIndex] == null) || !checkNamePattern(attributeNames[attributeIndex])) {
					throw new IllegalArgumentException("attributeName is not legal");
				}
				addAttribute(attributeNames[attributeIndex], attributeValues[attributeIndex]);
			}
		}
		this.name = name.toLowerCase(Locale.ENGLISH);
		if (content != null && !("#").equals(name)&& !("%").equals(name)) {
			addChild(new HTMLNode("#", content));
			this.content = null;
		} else
			this.content = content;
	}

	/**
	 * @return the content
	 */
	public String getContent() {
		return content;
	}

	public void addAttribute(String attributeName, String attributeValue) {
		if(readOnly)
			throw new IllegalArgumentException("Read only");
		if (attributeName == null)
			throw new IllegalArgumentException("Cannot add an attribute with a null name");
		if (attributeValue == null)
			throw new IllegalArgumentException("Cannot add an attribute with a null value");
		attributes.put(attributeName, attributeValue);
	}

	public Map<String, String> getAttributes() {
		return Collections.unmodifiableMap(attributes);
	}

	public String getAttribute(String attributeName) {
		return attributes.get(attributeName);
	}

	public HTMLNode addChild(HTMLNode childNode) {
		if(readOnly)
			throw new IllegalArgumentException("Read only");
		if (childNode == null) throw new NullPointerException();
		//since an efficient algorithm to check the loop presence 
		//is not present, at least it checks if we are trying to
		//addChild the node itself as a child
		if (childNode == this)	
			throw new IllegalArgumentException("A HTMLNode cannot be child of himself");
		if (children.contains(childNode))
			throw new IllegalArgumentException("Cannot add twice the same HTMLNode as child");
		children.add(childNode);
		return childNode;
	}
	
	public void addChildren(HTMLNode[] childNodes) {
		if(readOnly)
			throw new IllegalArgumentException("Read only");
		for (HTMLNode childNode: childNodes) {
			addChild(childNode);
		}
	}

	public HTMLNode addChild(String nodeName) {
		return addChild(nodeName, null);
	}

	public HTMLNode addChild(String nodeName, String content) {
		return addChild(nodeName, (String[]) null, (String[]) null, content);
	}

	public HTMLNode addChild(String nodeName, String attributeName, String attributeValue) {
		return addChild(nodeName, attributeName, attributeValue, null);
	}

	public HTMLNode addChild(String nodeName, String attributeName, String attributeValue, String content) {
		return addChild(nodeName, new String[] { attributeName }, new String[] { attributeValue }, content);
	}

	public HTMLNode addChild(String nodeName, String[] attributeNames, String[] attributeValues) {
		return addChild(nodeName, attributeNames, attributeValues, null);
	}

	public HTMLNode addChild(String nodeName, String[] attributeNames, String[] attributeValues, String content) {
		return addChild(new HTMLNode(nodeName, attributeNames, attributeValues, content));
	}

	/**
	 * Returns the name of the first "real" tag found in the hierarchy below
	 * this node.
	 * 
	 * @return The name of the first "real" tag, or <code>null</code> if no
	 *         "real" tag could be found
	 */
	public String getFirstTag() {
		if (!"#".equals(name)) {
			return name;
		}
		for (int childIndex = 0, childCount = children.size(); childIndex < childCount; childIndex++) {
			HTMLNode childNode = children.get(childIndex);
			String tag = childNode.getFirstTag();
			if (tag != null) {
				return tag;
			}
		}
		return null;
	}

	public String generate() {
		StringBuilder tagBuffer = new StringBuilder();
		return generate(tagBuffer).toString();
	}

	public StringBuilder generate(StringBuilder tagBuffer) {
		return generate(tagBuffer,0);
	}

	public StringBuilder generate(StringBuilder tagBuffer, int indentDepth ) {
		if("#".equals(name)) {
			if(content != null) {
				HTMLEncoder.encodeToBuffer(content, tagBuffer);
				return tagBuffer;
			}
			
			for(int childIndex = 0, childCount = children.size(); childIndex < childCount; childIndex++) {
				HTMLNode childNode = children.get(childIndex);
				childNode.generate(tagBuffer);
			}
			return tagBuffer;
		}
		// Perhaps this should be something else, but since I don't know if '#' was not just arbitrary chosen, I'll just pick '%'
		// This allows non-encoded text to be appended to the tag buffer
		if ("%".equals(name)) {
			tagBuffer.append(content);
			return tagBuffer;
		}
		/* start the open tag */
		tagBuffer.append('<').append(name);

		/* add attributes*/
		Set<Map.Entry<String, String>> attributeSet = attributes.entrySet();
		for (Map.Entry<String, String> attributeEntry : attributeSet) {
			String attributeName = attributeEntry.getKey();
			String attributeValue = attributeEntry.getValue();
			tagBuffer.append(' ');
			HTMLEncoder.encodeToBuffer(attributeName, tagBuffer);
			tagBuffer.append("=\"");
			HTMLEncoder.encodeToBuffer(attributeValue, tagBuffer);
			tagBuffer.append('"');
		}

		/* complete the open tag*/
		tagBuffer.append(OpenSuffix(name));

		/*insert the contents*/
		if (children.size() == 0) {
			if(content==null) {
			} else {
				HTMLEncoder.encodeToBuffer(content, tagBuffer);
			}
		} else {
			if (newlineOpen(name)) {
				tagBuffer.append('\n');
				tagBuffer.append(indentString(indentDepth+1));
			}
			for (int childIndex = 0, childCount = children.size(); childIndex < childCount; childIndex++) {
				HTMLNode childNode = children.get(childIndex);
				childNode.generate(tagBuffer,indentDepth+1);
			}
		}
		/* add a closing tag */
		if (newlineOpen(name)) {
			tagBuffer.append('\n');
			tagBuffer.append(indentString(indentDepth));
		}
		tagBuffer.append(CloseTag(name));
		if (newlineClose(name)) {
			tagBuffer.append('\n');
			tagBuffer.append(indentString(indentDepth));
		}
		return tagBuffer;
	}
	
	public String generateChildren(){
		if(content!=null){
			return content;
		}
		StringBuilder tagBuffer=new StringBuilder();
		for(int childIndex = 0, childCount = children.size(); childIndex < childCount; childIndex++) {
			HTMLNode childNode = children.get(childIndex);
			childNode.generate(tagBuffer);
		}
		return tagBuffer.toString();
	}
	
	public void setContent(String newContent){
		if(readOnly)
			throw new IllegalArgumentException("Read only");
		content=newContent;
	}
	
	public List<HTMLNode> getChildren(){
		return children;
	}

	/**
	 * Special HTML node for the DOCTYPE declaration. This node differs from a
	 * normal HTML node in that it's child (and it should only have exactly one
	 * child, the "html" node) is rendered <em>after</em> this node.
	 * 
	 * @author David 'Bombe' Roden &lt;bombe@freenetproject.org&gt;
	 * @version $Id$
	 */
	public static class HTMLDoctype extends HTMLNode {

		private final String systemUri;

		/**
		 * 
		 */
		public HTMLDoctype(String doctype, String systemUri) {
			super(doctype);
			this.systemUri = systemUri;
		}

		/**
		 * @see freenet.support.HTMLNode#generate(java.lang.StringBuilder)
		 */
		@Override
		public StringBuilder generate(StringBuilder tagBuffer) {
			tagBuffer.append("<!DOCTYPE ").append(name).append(" PUBLIC \"").append(systemUri).append("\">\n");
			//TODO A meaningful exception should be raised 
			// when trying to call the method for a HTMLDoctype 
			// with number of child != 1 
			return children.get(0).generate(tagBuffer);
		}

	}

	public static HTMLNode link(String path) {
		return new HTMLNode("a", "href", path);
	}

	public static HTMLNode linkInNewWindow(String path) {
		return new HTMLNode("a", new String[] { "href", "target" }, new String[] { path, "_blank" });
	}

	public static HTMLNode text(String text) {
		return new HTMLNode("#", text);
	}
	
	public static HTMLNode text(int count) {
		return new HTMLNode("#", Integer.toString(count));
	}

	public static HTMLNode text(long count) {
		return new HTMLNode("#", Long.toString(count));
	}

	public static HTMLNode text(short count) {
		return new HTMLNode("#", Short.toString(count));
	}
}
