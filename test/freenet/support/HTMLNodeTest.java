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
	private static final String EXAMPLE_NODE_NAME = "ex\u03b1mpleNode";
	
	//example node attribute that includes a not ASCII char [greek beta]
	private static final String EXAMPLE_ATTRIBUTE_NAME = "exampleAttri\u03b2uteName";
	
	//example node attribute value that includes a not ASCII char [greek epsilon]
	private static final String EXAMPLE_ATTRIBUTE_VALUE = "exampleAttribut\u03b5Value";
	
	//example node content that includes a not ASCII char [greek omicron]
	private static final String EXAMPLE_NODE_CONTENT = "exampleNodeC\u03bfntent";
	
	protected void setUp() throws Exception {
		super.setUp();
		exampleNode = new HTMLNode(EXAMPLE_NODE_NAME);
	}
	
	/**
	 * Test HTMLNode(String,String,String,String) constructor
	 * using non-ASCII chars
	 */
	public void testNotAsciiHTMLNode_StringStringStringString() {
		HTMLNode methodHTMLNode = new HTMLNode(EXAMPLE_NODE_NAME,
				EXAMPLE_ATTRIBUTE_NAME,EXAMPLE_ATTRIBUTE_VALUE,
				EXAMPLE_NODE_CONTENT);
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
		HTMLNode[] methodHTMLNodesArray = {new HTMLNode(EXAMPLE_NODE_NAME),
										   exampleNode,
										   new HTMLNode(EXAMPLE_NODE_NAME+"1")};
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
			exampleNode.addChild(EXAMPLE_NODE_NAME);
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
		HTMLNode methodHTMLNode = new HTMLNode(EXAMPLE_NODE_NAME);
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
		HTMLNode methodHTMLNode = new HTMLNode(EXAMPLE_NODE_NAME);
		HTMLNode[] methodHTMLNodesArray = {methodHTMLNode,
										   methodHTMLNode};
		try {
			exampleNode.addChildren(methodHTMLNodesArray);
			fail("Expected Exception Error Not Thrown!"); } 
		catch (IllegalArgumentException anException) {
			assertNotNull(anException); }
		
	}

}
