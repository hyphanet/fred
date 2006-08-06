package freenet.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HTMLNode {

	private final String name;

	private final String content;

	private final Map attributes = new HashMap();

	private final List children = new ArrayList();

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
		children.add(childNode);
		return childNode;
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
			tagBuffer.append(" />");
		} else {
			tagBuffer.append(">");
			for (int childIndex = 0, childCount = children.size(); childIndex < childCount; childIndex++) {
				HTMLNode childNode = (HTMLNode) children.get(childIndex);
				childNode.generate(tagBuffer);
			}
			tagBuffer.append("</").append(name).append(">");
		}
		return tagBuffer;
	}

}