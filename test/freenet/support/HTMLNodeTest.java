/*
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package freenet.support;

import junit.framework.TestCase;

/**
 * Test case for {@link freenet.support.HTMLNode} class.
 *
 * @author Alberto Bacchelli &lt;sback@freenetproject.org&gt;
 */
public class HTMLNodeTest extends TestCase {
	
	private HTMLNode exampleNode;
	
	//example node name that includes a not ASCII char [greek alpha]
	private static final String SAMPLE_NODE_NAME = "s\u03b1mpleNode";
	
	//example node attribute that includes a not ASCII char [greek beta]
	private static final String SAMPLE_ATTRIBUTE_NAME = "sampleAttri\u03b2uteName";
	
	//example node attribute value that includes a not ASCII char [greek epsilon]
	private static final String SAMPLE_ATTRIBUTE_VALUE = "sampleAttribut\u03b5Value";
	
	//example node content that includes a not ASCII char [greek omicron]
	private static final String SAMPLE_NODE_CONTENT = "sampleNodeC\u03bfntent";
	
	protected void setUp() throws Exception {
		super.setUp();
		exampleNode = new HTMLNode(SAMPLE_NODE_NAME);
	}
	
	/**
	 * Test HTMLNode(String,String,String,String) constructor
	 * using non-ASCII chars
	 */
	public void testNotAsciiHTMLNode_StringStringStringString() {
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_NODE_NAME,
				SAMPLE_ATTRIBUTE_NAME,SAMPLE_ATTRIBUTE_VALUE,
				SAMPLE_NODE_CONTENT);
		assertFalse(exampleNode.children.contains(methodHTMLNode));
		exampleNode.addChild(methodHTMLNode);
		assertTrue(exampleNode.children.contains(methodHTMLNode));
	}
	
	/**
	 * Tests addAttribute(String,String) method
	 * adding the same attribute many times
	 * and verifying it keeps only one
	 * reference to it.
	 */
	public void testSameAttributeManyTimes() {
		int times = 100;
		String methodAttributeName = "exampleAttributeName";
		String methodAttributeValue = "exampleAttributeValue";
		for (int i = 0; i < times; i++) {
			exampleNode.addAttribute(methodAttributeName,methodAttributeValue);
			assertEquals(exampleNode.getAttributes().size(),1);
		}
	}
	
	/**
	 * Tests addChild(HTMLNode) method
	 * adding the Node itself as its
	 * child. The method should rise an exception
	 */
	public void testAddChildUsingTheNodeItselfAsChild() {
		try {
			exampleNode.addChild(exampleNode);
	    	fail("Expected Exception Error Not Thrown!"); } 
		catch (IllegalArgumentException anException) {
			assertNotNull(anException); }
	}
	
	/**
	 * Tests addChildren(HTMLNode[]) method
	 * adding the Node itself as its
	 * child. The method should rise an exception
	 */
	public void testAddChildrenUsingTheNodeItselfAsChild() {
		HTMLNode[] methodHTMLNodesArray = {new HTMLNode(SAMPLE_NODE_NAME),
										   exampleNode,
										   new HTMLNode(SAMPLE_NODE_NAME+"1")};
		try {
			exampleNode.addChildren(methodHTMLNodesArray);
			fail("Expected Exception Error Not Thrown!"); } 
		catch (IllegalArgumentException anException) {
			assertNotNull(anException); }
	}
	
	/**
	 * Tests addChild(String) method
	 * using the same name every time
	 * and verifying that a real new 
	 * HTML is always added.
	 */
	public void testAddChildSameName() {
		int times = 100;
		for (int i = 1; i<=times; i++) {
			exampleNode.addChild(SAMPLE_NODE_NAME);
			assertEquals(exampleNode.children.size(),i);
		}
	}
	
	/**
	 * Tests addChild(HTMLNode) method
	 * verifying the behavior when adding
	 * the same HTMLNode instance two times.
	 * It should raise an IllegalArgument exception.
	 */
	public void testAddChildSameObject() {
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_NODE_NAME);
		exampleNode.addChild(methodHTMLNode);
		try {
			exampleNode.addChild(methodHTMLNode);
			fail("Expected Exception Error Not Thrown!"); } 
		catch (IllegalArgumentException anException) {
			assertNotNull(anException); }
	}
	
	/**
	 * Tests addChildren(HTMLNode[]) method
	 * verifying the behavior when adding
	 * the same HTMLNode instance two times.
	 */
	public void testAddChildrenSameObject() {
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_NODE_NAME);
		HTMLNode[] methodHTMLNodesArray = {methodHTMLNode,
										   methodHTMLNode};
		try {
			exampleNode.addChildren(methodHTMLNodesArray);
			fail("Expected Exception Error Not Thrown!"); } 
		catch (IllegalArgumentException anException) {
			assertNotNull(anException); }
	}
	
	/**
	 * Tests getContent() method using
	 * common sample HTMLNode, and "#"
	 * "%" named nodes
	 */
	public void testGetContent() {
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_NODE_NAME);
		assertNull(methodHTMLNode.getContent());
		
		methodHTMLNode = new HTMLNode(SAMPLE_NODE_NAME,SAMPLE_NODE_CONTENT);
		//since the HTMLNode name is not "#", or "%",
		//the content will be a new child with the "#" name
		assertEquals(SAMPLE_NODE_CONTENT,
				((HTMLNode)(methodHTMLNode.children.get(0))).getContent());
		assertNull(methodHTMLNode.getContent());
		
		methodHTMLNode = new HTMLNode("#",SAMPLE_NODE_CONTENT);
		assertEquals(SAMPLE_NODE_CONTENT,
				methodHTMLNode.getContent());
		methodHTMLNode = new HTMLNode("%",SAMPLE_NODE_CONTENT);
		assertEquals(SAMPLE_NODE_CONTENT,
				methodHTMLNode.getContent());
	}
	
	/**
	 * Tests getAttribute() method using
	 * common sample HTMLNode, and "#"
	 * "%" named nodes
	 */
	public void testGetAttribute() {
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_NODE_NAME);
		assertNull(methodHTMLNode.getAttribute(SAMPLE_ATTRIBUTE_NAME));
		
		methodHTMLNode = new HTMLNode(SAMPLE_NODE_NAME,SAMPLE_ATTRIBUTE_NAME,SAMPLE_ATTRIBUTE_VALUE);
		assertEquals(SAMPLE_ATTRIBUTE_VALUE,methodHTMLNode.getAttribute(SAMPLE_ATTRIBUTE_NAME));
		methodHTMLNode = new HTMLNode("#",SAMPLE_ATTRIBUTE_NAME,SAMPLE_ATTRIBUTE_VALUE);
		assertEquals(SAMPLE_ATTRIBUTE_VALUE,methodHTMLNode.getAttribute(SAMPLE_ATTRIBUTE_NAME));
		methodHTMLNode = new HTMLNode("%",SAMPLE_ATTRIBUTE_NAME,SAMPLE_ATTRIBUTE_VALUE);
		assertEquals(SAMPLE_ATTRIBUTE_VALUE,methodHTMLNode.getAttribute(SAMPLE_ATTRIBUTE_NAME));
	}
	
	/**
	 * Tests getAttributes() and setAttribute(String,String)
	 * methods verifying if attributes are correctly
	 * inserted and fetched.
	 */
	public void testAddGetAttributes() {
		int attributesNumber = 100;
		String methodAttributeName = "";
		String counterString = "";
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_NODE_NAME);
		for (int i=0; i<attributesNumber; i++) {
			counterString = String.valueOf(i);
			methodAttributeName = "attribute " + counterString; 
			assertEquals(i,methodHTMLNode.getAttributes().size());
			methodHTMLNode.addAttribute(methodAttributeName,counterString);
			assertEquals(counterString,methodHTMLNode.getAttribute(methodAttributeName));
			assertEquals(counterString,methodHTMLNode.getAttributes().get(methodAttributeName));
		}
	}
	
	/**
	 * Tests addAttribute(String,String) method
	 * trying to insert an attribute with a null
	 * as name value. It should rise an
	 * IllegalArgument exception 
	 */
	public void testAddAttribute_nullAttributeName() {
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_NODE_NAME);
		try {
			methodHTMLNode.addAttribute(null,SAMPLE_ATTRIBUTE_VALUE);
			fail("Expected Exception Error Not Thrown!"); } 
		catch (IllegalArgumentException anException) {
			assertNotNull(anException); }
	}
	
	/**
	 * Tests addAttribute(String,String) method
	 * trying to insert an attribute with a null
	 * as attribute value. It should rise an
	 * IllegalArgument exception 
	 */
	public void testAddAttribute_nullAttributeValue() {
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_NODE_NAME);
		try {
			methodHTMLNode.addAttribute(SAMPLE_ATTRIBUTE_NAME,null);
			fail("Expected Exception Error Not Thrown!"); } 
		catch (IllegalArgumentException anException) {
			assertNotNull(anException); }
	}
	
	/**
	 * Fetches the first line of a String
	 * @param aString the String to consider
	 * @return the first line of the String
	 */
	private String readFirstLine(String aString) {
		int newLineIndex = aString.indexOf("\n");
		if ( newLineIndex == -1)
			return aString;
		return aString.substring(0,newLineIndex);
	}
	
	/**
	 * Tests generate() method with a
	 * HTMLNode with only the name.
	 * The resulting string should be in the form:
	 * <node_name />
	 */
	public void testGenerate_fromHTMLNode_String() {
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_NODE_NAME);
		assertEquals(("<"+SAMPLE_NODE_NAME+" />").toLowerCase(),
					methodHTMLNode.generate());
	}
	
	/**
	 * Tests generate() method with a
	 * HTMLNode with the name and content.
	 * The resulting string should be in the form:
	 * <node_name>Node_Content</node_name>
	 */
	public void testGenerate_fromHTMLNode_StringString() {
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_NODE_NAME,SAMPLE_NODE_CONTENT);
		assertEquals(("<"+SAMPLE_NODE_NAME+">").toLowerCase() + 
					 SAMPLE_NODE_CONTENT +
					 ("</"+SAMPLE_NODE_NAME+">").toLowerCase(),
					methodHTMLNode.generate());
	}
	
	/**
	 * Tests generate() method with a
	 * HTMLNode with the name, an attribute and its value.
	 * The resulting string should be in the form:
	 * <node_name Attribute_Name="Attribute_Value" />
	 */
	public void testGenerate_fromHTMLNode_StringStringString() {
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_NODE_NAME,
				SAMPLE_ATTRIBUTE_NAME,SAMPLE_ATTRIBUTE_VALUE);
		assertEquals(("<"+SAMPLE_NODE_NAME+" ").toLowerCase() + 
					 SAMPLE_ATTRIBUTE_NAME + "=" +
					 "\""+SAMPLE_ATTRIBUTE_VALUE+"\""+
					 " />",
					methodHTMLNode.generate());
	}
	
	/**
	 * Tests generate() method with a
	 * HTMLNode with the name, an attribute and its value.
	 * The resulting string should be in the form:
	 * <node_name Attribute_Name="Attribute_Value">Node_Content</node_name>
	 */
	public void testGenerate_fromHTMLNode_StringStringStringString() {
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_NODE_NAME,
				SAMPLE_ATTRIBUTE_NAME,SAMPLE_ATTRIBUTE_VALUE,
				SAMPLE_NODE_CONTENT);
		assertEquals(generateFullNodeOutput(SAMPLE_NODE_NAME,
				SAMPLE_ATTRIBUTE_NAME, SAMPLE_ATTRIBUTE_VALUE, 
				SAMPLE_NODE_CONTENT),
					methodHTMLNode.generate());
	}
	
	/**
	 * Generates the correct output for the HTMLNode.generate() method
	 * when called from a single node having the specified parameters
	 * @param aName the HTMLNode name
	 * @param aAttributeName the HTMLNode attribute name
	 * @param aAttributeValue the HTMLNode attribute value
	 * @param aContent the HTMLNode content
	 * @return the correct output expected by HTMLNode.generate() method
	 */
	private String generateFullNodeOutput(String aName, String aAttributeName, String aAttributeValue, String aContent) {
		return ("<"+aName+" ").toLowerCase() + 
		aAttributeName + "=" +
		 "\""+aAttributeValue+"\">" +
		 aContent +
		 ("</"+aName+">").toLowerCase();
	}
	
	/**
	 * Tests generate() method with a
	 * HTMLNode that has a child.
	 * <node_name Attribute_Name="Attribute_Value">Node_Content
	 * <child_node_name child_Attribute_Name="child_Attribute_Value">child_Node_Content</child_node_name>
	 * </node_name>
	 */
	public void testGenerate_HTMLNode_withChild() {
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_NODE_NAME,
				SAMPLE_ATTRIBUTE_NAME,SAMPLE_ATTRIBUTE_VALUE,
				SAMPLE_NODE_CONTENT);
		HTMLNode methodHTMLNodeChild = new HTMLNode(SAMPLE_NODE_NAME,
				SAMPLE_ATTRIBUTE_NAME,SAMPLE_ATTRIBUTE_VALUE,
				SAMPLE_NODE_CONTENT);
		
		methodHTMLNode.addChild(methodHTMLNodeChild);
		
		assertEquals(("<"+SAMPLE_NODE_NAME+" ").toLowerCase() + 
				 SAMPLE_ATTRIBUTE_NAME + "=" +
				 "\""+SAMPLE_ATTRIBUTE_VALUE+"\">" +
				 SAMPLE_NODE_CONTENT +
				 
				 //child
				 generateFullNodeOutput(SAMPLE_NODE_NAME,
							SAMPLE_ATTRIBUTE_NAME, SAMPLE_ATTRIBUTE_VALUE, 
							SAMPLE_NODE_CONTENT) +
				 
				 ("</"+SAMPLE_NODE_NAME+">").toLowerCase(),
				 methodHTMLNode.generate());
	}
	
	/**
	 * Tests HTMLDoctype.generate() method
	 * comparing the result with the expected
	 * String. It is useful for regression tests.
	 */
	public void testHTMLDoctype_generate() {
		String sampleDocType = "html";
		String sampleSystemUri = "-//W3C//DTD XHTML 1.1//EN";
		HTMLNode methodHTMLNodeDoc = new HTMLNode.HTMLDoctype(sampleDocType,sampleSystemUri);
		methodHTMLNodeDoc.addChild(SAMPLE_NODE_NAME);
		String generatedString = methodHTMLNodeDoc.generate();
		//consider only the HTMLDocType generated text
		assertEquals("<!DOCTYPE "+sampleDocType+" PUBLIC \""+sampleSystemUri+"\">", 	
				readFirstLine(generatedString));
		
	}

}
