package freenet.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class HTMLNode implements XMLCharacterClasses {
	
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
		for (int i = 0, c = childNodes.length; i < c; i++) {
			addChild(childNodes[i]);
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
		tagBuffer.append('<').append(name);
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
		if (children.size() == 0) {
			if(content==null){
				if ("textarea".equals(name) || ("div").equals(name) || ("a").equals(name) || ("script").equals(name)) {
					tagBuffer.append("></");
					tagBuffer.append(name);
					tagBuffer.append('>');
				} else {
					tagBuffer.append(" />");
				}
			}else{
				tagBuffer.append(">");
				HTMLEncoder.encodeToBuffer(content, tagBuffer);
				tagBuffer.append("</");
				tagBuffer.append(name);
				tagBuffer.append(">");
			}
			
		} else {
			if(("div").equals(name) || ("form").equals(name) || ("input").equals(name) || ("script").equals(name) || ("table").equals(name) || ("tr").equals(name) || ("td").equals(name)) {
				tagBuffer.append('\n');
			}
			tagBuffer.append('>');
			for (int childIndex = 0, childCount = children.size(); childIndex < childCount; childIndex++) {
				HTMLNode childNode = children.get(childIndex);
				childNode.generate(tagBuffer);
			}
			tagBuffer.append("</");
			tagBuffer.append(name);
			if(("div").equals(name)|| ("form").equals(name)|| ("input").equals(name)|| ("li").equals(name)|| ("option").equals(name)|| ("script").equals(name)|| ("table").equals(name)|| ("tr").equals(name)|| ("td").equals(name)) {
				tagBuffer.append('\n');
			}
			tagBuffer.append('>');
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
