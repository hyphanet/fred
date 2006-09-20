package freenet.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HTMLNode {

	protected final String name;

	private final String content;

	protected final Map attributes = new HashMap();

	protected final List children = new ArrayList();

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

	public HTMLNode(String name, String[] attributeNames, String[] attributeValues, String content) {
		this.name = name;
		if ((attributeNames != null) && (attributeValues != null)) {
			if (attributeNames.length != attributeValues.length) {
				throw new IllegalArgumentException("attribute names and values differ");
			}
			for (int attributeIndex = 0, attributeCount = attributeNames.length; attributeIndex < attributeCount; attributeIndex++) {
				attributes.put(attributeNames[attributeIndex], attributeValues[attributeIndex]);
			}
		}
		if (content != null) {
			if (!name.equals("#")) {
				addChild(new HTMLNode("#", content));
				this.content = null;
			} else {
				this.content = content;
			}
		} else {
			this.content = null;
		}
	}

	/**
	 * @return the name
	 */
	public String getName() {
		return name;
	}

	/**
	 * @return the content
	 */
	public String getContent() {
		return content;
	}

	public void addAttribute(String attributeName) {
		addAttribute(attributeName, attributeName);
	}

	public void addAttribute(String attributeName, String attributeValue) {
		attributes.put(attributeName, attributeValue);
	}

	public Map getAttributes() {
		return Collections.unmodifiableMap(attributes);
	}

	public String getAttribute(String attributeName) {
		return (String) attributes.get(attributeName);
	}

	public HTMLNode addChild(HTMLNode childNode) {
		if (childNode == null) throw new NullPointerException();
		children.add(childNode);
		return childNode;
	}
	
	public void addChildren(HTMLNode[] childNodes) {
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

	public String generate() {
		StringBuffer tagBuffer = new StringBuffer();
		return generate(tagBuffer).toString();
	}

	public StringBuffer generate(StringBuffer tagBuffer) {
		if (name.equals("#")) {
			tagBuffer.append(HTMLEncoder.encode(content));
			return tagBuffer;
		}
		tagBuffer.append("<").append(name);
		Set attributeSet = attributes.entrySet();
		for (Iterator attributeIterator = attributeSet.iterator(); attributeIterator.hasNext();) {
			Map.Entry attributeEntry = (Map.Entry) attributeIterator.next();
			String attributeName = (String) attributeEntry.getKey();
			String attributeValue = (String) attributeEntry.getValue();
			tagBuffer.append(" ").append(HTMLEncoder.encode(attributeName)).append("=\"").append(HTMLEncoder.encode(attributeValue)).append("\"");
		}
		if (children.size() == 0) {
			if (name.equals("textarea") || name.equals("div") || name.equals("a")) {
				tagBuffer.append("></" + name + ">");
			} else {
				tagBuffer.append(" />");
			}
		} else {
			tagBuffer.append(">");
			if(name.equals("div") || name.equals("table") || name.equals("tr") || name.equals("td")) {
				tagBuffer.append("\n");
			}
			for (int childIndex = 0, childCount = children.size(); childIndex < childCount; childIndex++) {
				HTMLNode childNode = (HTMLNode) children.get(childIndex);
				childNode.generate(tagBuffer);
			}
			tagBuffer.append("</").append(name).append(">");
			if(name.equals("div") || name.equals("li") || name.equals("table") || name.equals("tr") || name.equals("td")) {
				tagBuffer.append("\n");
			}
		}
		return tagBuffer;
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

		protected final String systemUri;

		/**
		 * 
		 */
		public HTMLDoctype(String doctype, String systemUri) {
			super(doctype);
			this.systemUri = systemUri;
		}

		/**
		 * @see freenet.support.HTMLNode#generate(java.lang.StringBuffer)
		 */
		public StringBuffer generate(StringBuffer tagBuffer) {
			tagBuffer.append("<!DOCTYPE ").append(name).append(" PUBLIC \"").append(systemUri).append("\">\n");
			return ((HTMLNode) children.get(0)).generate(tagBuffer);
		}

	}

}
