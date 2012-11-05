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

import java.util.List;

import junit.framework.TestCase;

/**
 * Test case for {@link freenet.support.HTMLNode} class.
 *
 * @author Alberto Bacchelli &lt;sback@freenetproject.org&gt;
 */
public class HTMLNodeTest extends TestCase {
	
	private HTMLNode exampleNodeNonEmpty;
	private HTMLNode exampleNodeEmpty;
	
	/** Example node name in ASCII only. Not permitted to be empty. */
	private static final String SAMPLE_OKAY_NODE_NAME_NON_EMPTY = "sampleNode";
	
	/** Example node name in ASCII only. Not permitted to be empty. It must be on the 
	 * EmptyTag list, so we use a real tag name */
	private static final String SAMPLE_OKAY_NODE_NAME_EMPTY = "area";
	
	/** Example node name that includes an invalid char. */
	private static final String SAMPLE_WRONG_NODE_NAME = "s\u03a2mpleNode";
	
	/* example node attribute in ASCII only. */
	private static final String SAMPLE_OKAY_ATTRIBUTE_NAME = "sampleAttributeName";

	/** Example attribute name that includes an invalid char. */
	private static final String SAMPLE_WRONG_ATTRIBUTE_NAME = "s\u03a2mpleAttributeName";

	//example node attribute value that includes a not ASCII char [Greek epsilon]
	private static final String SAMPLE_ATTRIBUTE_VALUE = "sampleAttribut\u03b5Value";
	
	//example node content that includes a not ASCII char [Greek omicron]
	private static final String SAMPLE_NODE_CONTENT = "sampleNodeC\u03bfntent";
	
	@Override
	protected void setUp() throws Exception {
		super.setUp();
		exampleNodeNonEmpty = null;
		exampleNodeEmpty = null;
		try {
			exampleNodeNonEmpty = new HTMLNode(SAMPLE_OKAY_NODE_NAME_NON_EMPTY);
			exampleNodeEmpty = new HTMLNode(SAMPLE_OKAY_NODE_NAME_EMPTY);
		} catch (IllegalArgumentException iae1) {
			fail("Unexpected exception thrown!");
		}
		assertNotNull(exampleNodeNonEmpty);
		assertNotNull(exampleNodeEmpty);
		assertEquals(0, exampleNodeEmpty.children.size());
		assertEquals(0, exampleNodeNonEmpty.children.size());
	}
	
	/**
	 * Tests HTMLNode(String,String,String,String) constructor
	 * using non-ASCII chars
	 */
	public void testHTMLNode_StringStringStringString_WrongNodeName() {
		try {
			new HTMLNode(SAMPLE_WRONG_NODE_NAME, SAMPLE_OKAY_ATTRIBUTE_NAME, SAMPLE_ATTRIBUTE_VALUE, SAMPLE_NODE_CONTENT);
			fail("Expected exception not thrown!");
		} catch (IllegalArgumentException iae1) {
		}
		try {
			new HTMLNode(SAMPLE_OKAY_NODE_NAME_NON_EMPTY, SAMPLE_OKAY_ATTRIBUTE_NAME, SAMPLE_ATTRIBUTE_VALUE, SAMPLE_NODE_CONTENT);
		} catch (IllegalArgumentException iae1) {
			fail("Unexpected exception thrown!");
		}
	}
	
	public void testHTMLNode_StringStringStringString_WrongAttributeName() {
		try {
			new HTMLNode(SAMPLE_OKAY_NODE_NAME_NON_EMPTY, SAMPLE_WRONG_ATTRIBUTE_NAME, SAMPLE_ATTRIBUTE_VALUE, SAMPLE_NODE_CONTENT);
			fail("Expected exception not thrown!");
		} catch (IllegalArgumentException iae1) {
		}
		try {
			new HTMLNode(SAMPLE_OKAY_NODE_NAME_NON_EMPTY, SAMPLE_OKAY_ATTRIBUTE_NAME, SAMPLE_ATTRIBUTE_VALUE, SAMPLE_NODE_CONTENT);
		} catch (IllegalArgumentException iae1) {
			fail("Unexpected exception thrown!");
		}
	}
	
	/**
	 * Tests HTMLNode(String,String[],String[],String) constructor
	 * verifying if all attributes are correctly inserted
	 */
	public void testHTMLNode_AttributesArray() {
		int size = 100;
		String[] methodAttributesName = new String[size];
		String[] methodAttributesValue = new String[size];
		for (int i=0;i<size;i++) {
			methodAttributesName[i] = "AttributeName" + i;
			methodAttributesValue[i] = "Value " + i;
		}
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_OKAY_NODE_NAME_NON_EMPTY,
				methodAttributesName,methodAttributesValue,
				SAMPLE_NODE_CONTENT);
		//checks presence
		for(int i=0;i<size;i++)
			assertEquals(methodAttributesValue[i],
					methodHTMLNode.getAttribute(methodAttributesName[i]));
		//checks size
		assertEquals(size,methodHTMLNode.getAttributes().size());
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
			exampleNodeNonEmpty.addAttribute(methodAttributeName,methodAttributeValue);
			assertEquals(exampleNodeNonEmpty.getAttributes().size(),1);
		}
	}
	
	/**
	 * Tests addChild(HTMLNode) method
	 * adding the Node itself as its
	 * child. The method should rise an exception
	 */
	public void testAddChildUsingTheNodeItselfAsChild() {
		try {
			exampleNodeNonEmpty.addChild(exampleNodeNonEmpty);
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
		HTMLNode[] methodHTMLNodesArray = {new HTMLNode(SAMPLE_OKAY_NODE_NAME_NON_EMPTY),
										   exampleNodeNonEmpty,
										   new HTMLNode(SAMPLE_OKAY_NODE_NAME_NON_EMPTY+"1")};
		try {
			exampleNodeNonEmpty.addChildren(methodHTMLNodesArray);
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
			exampleNodeNonEmpty.addChild(SAMPLE_OKAY_NODE_NAME_NON_EMPTY);
			assertEquals(exampleNodeNonEmpty.children.size(),i);
		}
	}
	
	/**
	 * Tests addChild(HTMLNode) method
	 * verifying the behavior when adding
	 * the same HTMLNode instance two times.
	 * It should raise an IllegalArgument exception.
	 */
	public void testAddChildSameObject() {
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_OKAY_NODE_NAME_NON_EMPTY);
		exampleNodeNonEmpty.addChild(methodHTMLNode);
		try {
			exampleNodeNonEmpty.addChild(methodHTMLNode);
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
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_OKAY_NODE_NAME_NON_EMPTY);
		HTMLNode[] methodHTMLNodesArray = {methodHTMLNode,
										   methodHTMLNode};
		try {
			exampleNodeNonEmpty.addChildren(methodHTMLNodesArray);
			fail("Expected Exception Error Not Thrown!"); } 
		catch (IllegalArgumentException anException) {
			assertNotNull(anException); }
	}
	
	/**
	 * Tests addChildren(String,String,String) method
	 * verifying if the child is correctly added
	 * and if it generates good output using generate() method.
	 */
	public void testAddChild_StringStringString() {
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_OKAY_NODE_NAME_EMPTY);
		methodHTMLNode.addChild(SAMPLE_OKAY_NODE_NAME_EMPTY, 
				SAMPLE_OKAY_ATTRIBUTE_NAME, SAMPLE_ATTRIBUTE_VALUE);
		List<HTMLNode> childrenList = methodHTMLNode.children;
		assertEquals(1,childrenList.size());
		assertEquals(generateNoContentNodeOutput(SAMPLE_OKAY_NODE_NAME_EMPTY,
				SAMPLE_OKAY_ATTRIBUTE_NAME,SAMPLE_ATTRIBUTE_VALUE),
				childrenList.get(0).generate());
	}
	
	/**
	 * Tests addChildren(String,String,String,String) method
	 * verifying if the child is correctly added
	 * and if it generates good output using generate() method.
	 */
	public void testAddChild_StringStringStringString() {
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_OKAY_NODE_NAME_NON_EMPTY);
		methodHTMLNode.addChild(SAMPLE_OKAY_NODE_NAME_NON_EMPTY, 
				SAMPLE_OKAY_ATTRIBUTE_NAME, SAMPLE_ATTRIBUTE_VALUE,
				SAMPLE_NODE_CONTENT);
		List<HTMLNode> childrenList = methodHTMLNode.children;
		assertEquals(1,childrenList.size());
		assertEquals(generateFullNodeOutput(SAMPLE_OKAY_NODE_NAME_NON_EMPTY,
				SAMPLE_OKAY_ATTRIBUTE_NAME, SAMPLE_ATTRIBUTE_VALUE, 
				SAMPLE_NODE_CONTENT),
					childrenList.get(0).generate());
	}
	
	/**
	 * Tests addChildren(String,String[],String[]) method
	 * verifying if the child is correctly added
	 * and the child attributes are corrects.
	 */
	public void testAddChild_StringArrayArray() {
		String[] methodAttributesNamesArray = {"firstName","secondName","thirdName"};
		String[] methodAttributesValuesArray = {"firstValue","secondValue","thirdValue"};
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_OKAY_NODE_NAME_NON_EMPTY);
		methodHTMLNode.addChild(SAMPLE_OKAY_NODE_NAME_NON_EMPTY, 
				methodAttributesNamesArray, methodAttributesValuesArray);
		testSingleChildAttributes(methodHTMLNode, 
				methodAttributesNamesArray, methodAttributesValuesArray);
	}
	
	/**
	 * Tests addChildren(String,String[],String[],String) method
	 * verifying if the child is correctly added
	 * and the child attributes are corrects.
	 */
	public void testAddChild_StringArrayArrayString() {
		String[] methodAttributesNamesArray = {"firstName","secondName","thirdName"};
		String[] methodAttributesValuesArray = {"firstValue","secondValue","thirdValue"};
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_OKAY_NODE_NAME_NON_EMPTY);
		methodHTMLNode.addChild(SAMPLE_OKAY_NODE_NAME_NON_EMPTY, 
				methodAttributesNamesArray, methodAttributesValuesArray,
				SAMPLE_NODE_CONTENT);
		testSingleChildAttributes(methodHTMLNode, 
				methodAttributesNamesArray, methodAttributesValuesArray);
	}
	
	/**
	 * Check the passed HTMLNode only child attributes
	 * @param aHTMLNode where we fetch the only child
	 * @param attibutesNames the attributes names to check
	 * @param attributesValues the attributes values to check
	 */
	private void testSingleChildAttributes(HTMLNode aHTMLNode,String[] attibutesNames, String[] attributesValues) {
		List<HTMLNode> childrenList = aHTMLNode.children;
		assertEquals(1,childrenList.size());
		HTMLNode childHTMLNode = childrenList.get(0);
		assertEquals(attibutesNames.length,childHTMLNode.getAttributes().size());
		for(int i = 0 ; i<attibutesNames.length;i++)
			assertEquals(attributesValues[i],
					childHTMLNode.getAttribute(attibutesNames[i]));
	}
	
	/**
	 * Tests getContent() method using
	 * common sample HTMLNode, and "#"
	 * "%" named nodes
	 */
	public void testGetContent() {
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_OKAY_NODE_NAME_NON_EMPTY);
		assertNull(methodHTMLNode.getContent());
		
		methodHTMLNode = new HTMLNode(SAMPLE_OKAY_NODE_NAME_NON_EMPTY,SAMPLE_NODE_CONTENT);
		//since the HTMLNode name is not "#", or "%",
		//the content will be a new child with the "#" name
		assertEquals(SAMPLE_NODE_CONTENT,
				methodHTMLNode.children.get(0).getContent());
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
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_OKAY_NODE_NAME_NON_EMPTY);
		assertNull(methodHTMLNode.getAttribute(SAMPLE_OKAY_ATTRIBUTE_NAME));
		
		methodHTMLNode = new HTMLNode(SAMPLE_OKAY_NODE_NAME_NON_EMPTY,SAMPLE_OKAY_ATTRIBUTE_NAME,SAMPLE_ATTRIBUTE_VALUE);
		assertEquals(SAMPLE_ATTRIBUTE_VALUE,methodHTMLNode.getAttribute(SAMPLE_OKAY_ATTRIBUTE_NAME));
		methodHTMLNode = new HTMLNode("#",SAMPLE_OKAY_ATTRIBUTE_NAME,SAMPLE_ATTRIBUTE_VALUE);
		assertEquals(SAMPLE_ATTRIBUTE_VALUE,methodHTMLNode.getAttribute(SAMPLE_OKAY_ATTRIBUTE_NAME));
		methodHTMLNode = new HTMLNode("%",SAMPLE_OKAY_ATTRIBUTE_NAME,SAMPLE_ATTRIBUTE_VALUE);
		assertEquals(SAMPLE_ATTRIBUTE_VALUE,methodHTMLNode.getAttribute(SAMPLE_OKAY_ATTRIBUTE_NAME));
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
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_OKAY_NODE_NAME_NON_EMPTY);
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
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_OKAY_NODE_NAME_NON_EMPTY);
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
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_OKAY_NODE_NAME_NON_EMPTY);
		try {
			methodHTMLNode.addAttribute(SAMPLE_WRONG_ATTRIBUTE_NAME,null);
			fail("Expected Exception Error Not Thrown!"); } 
		catch (IllegalArgumentException anException) {
			assertNotNull(anException); }
	}
	
	/**
	 * Tests HTMLNode(String,String,String,String) and
	 * HTMLNode(String,String,String) constructors 
	 * trying to create a node that has attribute name 
	 * null. It should raise an IllegalArgument exception
	 */
	public void testHTMLNode_nullAttributeName() {
		try {
			new HTMLNode(SAMPLE_OKAY_NODE_NAME_NON_EMPTY,
					null,SAMPLE_ATTRIBUTE_VALUE,
					SAMPLE_NODE_CONTENT);
			fail("Expected Exception Error Not Thrown!"); } 
		catch (IllegalArgumentException anException) {
			assertNotNull(anException); }
		try {
			new HTMLNode(SAMPLE_OKAY_NODE_NAME_NON_EMPTY,
					null,SAMPLE_ATTRIBUTE_VALUE);
			fail("Expected Exception Error Not Thrown!"); } 
		catch (IllegalArgumentException anException) {
			assertNotNull(anException); }
	}
	
	/**
	 * Tests HTMLNode(String,String,String,String) and
	 * HTMLNode(String,String,String) constructors 
	 * trying to create a node that has attribute value 
	 * null. It should raise an IllegalArgument exception
	 */
	public void testHTMLNode_nullAttributeValue() {
		try {
			new HTMLNode(SAMPLE_OKAY_NODE_NAME_NON_EMPTY,
					SAMPLE_WRONG_ATTRIBUTE_NAME,null,
					SAMPLE_NODE_CONTENT);
			fail("Expected Exception Error Not Thrown!"); } 
		catch (IllegalArgumentException anException) {
			assertNotNull(anException); }
		try {
			new HTMLNode(SAMPLE_OKAY_NODE_NAME_NON_EMPTY,
					SAMPLE_WRONG_ATTRIBUTE_NAME,null);
			fail("Expected Exception Error Not Thrown!"); } 
		catch (IllegalArgumentException anException) {
			assertNotNull(anException); }
	}
	
	/**
	 * Tests HTMLNode(String,String[],String[],String) 
	 * constructor trying to create a node that has
	 * attributes name null. It should raise an
	 * IllegalArgument exception
	 */
	public void testHTMLNodeArray_nullAttributeName() {
		String[] methodAttributesNameArray = {"first",null,"after"};
		String[] methodAttributesValueArray = {SAMPLE_ATTRIBUTE_VALUE,
				SAMPLE_ATTRIBUTE_VALUE,SAMPLE_ATTRIBUTE_VALUE};
		testHTMLNodeArray_null(methodAttributesNameArray, methodAttributesValueArray);
	}
	
	/**
	 * Tests HTMLNode(String,String[],String[],String) 
	 * constructor trying to create a node that has
	 * attributes value null. It should raise an
	 * IllegalArgument exception
	 */
	public void testHTMLNodeArray_nullAttributeValue() {
		String[] methodAttributesNameArray = {SAMPLE_WRONG_ATTRIBUTE_NAME,
				SAMPLE_WRONG_ATTRIBUTE_NAME,SAMPLE_WRONG_ATTRIBUTE_NAME};
		String[] methodAttributesValueArray = {"first",null,"after"};
		testHTMLNodeArray_null(methodAttributesNameArray, methodAttributesValueArray);
	}
	
	/**
	 * Tests HTMLNode(String,String[],String[],String) 
	 * constructor trying to create a node that has
	 * different length for attributes names array and
	 * attributes values array. It should raise an
	 * IllegalArgument exception
	 */
	public void testHTMLNode_attributeArrays_differentLengths() {
		String[] methodAttributesNameArray = {SAMPLE_WRONG_ATTRIBUTE_NAME,
				SAMPLE_WRONG_ATTRIBUTE_NAME};
		String[] methodAttributesValueArray = {SAMPLE_ATTRIBUTE_VALUE,
				SAMPLE_ATTRIBUTE_VALUE,SAMPLE_ATTRIBUTE_VALUE};
		testHTMLNodeArray_null(methodAttributesNameArray, methodAttributesValueArray);
	}
	
	/**
	 * Tests if the passed arrays raise an IllegalArgumentException
	 * using them to create a new HTMLNode (i.e. one of the name or value
	 * must be null)
	 * @param attributesNames the array of attribute names
	 * @param attributesValues the array of attribute values
	 */
	private void testHTMLNodeArray_null(String[] attributesNames, String[] attributesValues) {
		try {
			new HTMLNode(SAMPLE_OKAY_NODE_NAME_NON_EMPTY,
					attributesNames,attributesValues,
					SAMPLE_NODE_CONTENT);
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
	 * HTMLNode that has "textarea","div","a"
	 * as node name, since they generates a different
	 * output from all other names.
	 */
	public void testGenerate_fromHTMLNode_textareaDivA() {
		HTMLNode methodHTMLNode;
		String[] nodeNamesArray = {"textarea","div","a"};
		for(int i=0;i<nodeNamesArray.length;i++) {
			boolean newlines = new HTMLNode("a").newlineOpen(nodeNamesArray[i]);
			methodHTMLNode = new HTMLNode(nodeNamesArray[i],
					SAMPLE_OKAY_ATTRIBUTE_NAME,SAMPLE_ATTRIBUTE_VALUE);
			assertEquals(generateFullNodeOutput(nodeNamesArray[i], 
					SAMPLE_OKAY_ATTRIBUTE_NAME,SAMPLE_ATTRIBUTE_VALUE,"", newlines),
					methodHTMLNode.generate());
		}	
	}
	
	/**
	 * Tests generate() method when the
	 * node has a special name
	 * (i.e. "div","form","input","script","table","tr","td")
	 * and a child
	 */
	public void testGenerate_fromHTMLNodeWithChild_SpecialNames() {
		HTMLNode methodHTMLNode;
		String[] nodeNamesArray = {"div","form",// input is an empty element!
				"script","table","tr","td"};
		HTMLNode methodChildNode = new HTMLNode(SAMPLE_OKAY_NODE_NAME_NON_EMPTY,
				SAMPLE_OKAY_ATTRIBUTE_NAME,SAMPLE_ATTRIBUTE_VALUE,
				SAMPLE_NODE_CONTENT);
		for(int i=0;i<nodeNamesArray.length;i++) {
			methodHTMLNode = new HTMLNode(nodeNamesArray[i],
					SAMPLE_OKAY_ATTRIBUTE_NAME,SAMPLE_ATTRIBUTE_VALUE,
					SAMPLE_NODE_CONTENT);
			methodHTMLNode.addChild(methodChildNode);
			
			assertEquals(("<"+nodeNamesArray[i]+" ").toLowerCase() + 
					SAMPLE_OKAY_ATTRIBUTE_NAME + "=" +
					 "\""+SAMPLE_ATTRIBUTE_VALUE+"\">\n" +
					 // FIXME why is this using 2 tabs? I don't understand ...
					 "\t\t"+SAMPLE_NODE_CONTENT +
					 
					 //child
					 generateFullNodeOutput(SAMPLE_OKAY_NODE_NAME_NON_EMPTY,
							 SAMPLE_OKAY_ATTRIBUTE_NAME, SAMPLE_ATTRIBUTE_VALUE, 
								SAMPLE_NODE_CONTENT) +
					 "\n\t"+
					 ("</"+nodeNamesArray[i]+">\n").toLowerCase()
					 +"\t",
					 
					 methodHTMLNode.generate());
		}
	}
	
	/**
	 * Tests generate() method with a
	 * HTMLNode with only the name.
	 * The resulting string should be in the form:
	 * <node_name />
	 */
	public void testGenerate_fromHTMLNode_String() {
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_OKAY_NODE_NAME_EMPTY);
		assertEquals(("<"+SAMPLE_OKAY_NODE_NAME_EMPTY+" />").toLowerCase(),
					methodHTMLNode.generate());
	}
	
	/**
	 * Tests generate() method with a
	 * HTMLNode with the name and content.
	 * The resulting string should be in the form:
	 * <node_name>Node_Content</node_name>
	 */
	public void testGenerate_fromHTMLNode_StringString() {
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_OKAY_NODE_NAME_NON_EMPTY,SAMPLE_NODE_CONTENT);
		assertEquals(("<"+SAMPLE_OKAY_NODE_NAME_NON_EMPTY+">").toLowerCase() + 
					 SAMPLE_NODE_CONTENT +
					 ("</"+SAMPLE_OKAY_NODE_NAME_NON_EMPTY+">").toLowerCase(),
					methodHTMLNode.generate());
	}
	
	/**
	 * Tests generate() method with a
	 * HTMLNode with the name, an attribute and its value.
	 * The resulting string should be in the form:
	 * <node_name Attribute_Name="Attribute_Value" />
	 */
	public void testGenerate_fromHTMLNode_StringStringString() {
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_OKAY_NODE_NAME_EMPTY,
				SAMPLE_OKAY_ATTRIBUTE_NAME,SAMPLE_ATTRIBUTE_VALUE);
		assertEquals(generateNoContentNodeOutput(SAMPLE_OKAY_NODE_NAME_EMPTY,
				SAMPLE_OKAY_ATTRIBUTE_NAME,SAMPLE_ATTRIBUTE_VALUE),
					methodHTMLNode.generate());
	}
	
	/**
	 * Tests generate() method with a
	 * HTMLNode with the name, an attribute and its value.
	 * The resulting string should be in the form:
	 * <node_name Attribute_Name="Attribute_Value">Node_Content</node_name>
	 */
	public void testGenerate_fromHTMLNode_StringStringStringString() {
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_OKAY_NODE_NAME_NON_EMPTY,
				SAMPLE_OKAY_ATTRIBUTE_NAME,SAMPLE_ATTRIBUTE_VALUE,
				SAMPLE_NODE_CONTENT);
		assertEquals(generateFullNodeOutput(SAMPLE_OKAY_NODE_NAME_NON_EMPTY,
				SAMPLE_OKAY_ATTRIBUTE_NAME, SAMPLE_ATTRIBUTE_VALUE, 
				SAMPLE_NODE_CONTENT),
					methodHTMLNode.generate());
	}
	
	/**
	 * Generates the correct output for the HTMLNode.generate() method
	 * when called from a single node having only a name and an attribute
	 * name and value
	 * @param aName the HTMLNode name
	 * @param aAttributeName the HTMLNode attribute name
	 * @param aAttributeValue the HTMLNode attribute value
	 * @return the correct output expected by HTMLNode.generate() method
	 */
	private String generateNoContentNodeOutput(String aName, String aAttributeName, String aAttributeValue) {
		return ("<"+aName+" ").toLowerCase() + 
		aAttributeName + "=" +
		 "\""+aAttributeValue+"\""+
		 " />";
	}

	private String generateFullNodeOutput(String aName, String aAttributeName, String aAttributeValue, String aContent) {
		return generateFullNodeOutput(aName, aAttributeName, aAttributeValue, aContent, false);
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
	private String generateFullNodeOutput(String aName, String aAttributeName, String aAttributeValue, String aContent, boolean indent) {
		StringBuffer sb = new StringBuffer();
		sb.append("<"+aName.toLowerCase()+" ");
		sb.append(aAttributeName + "=");
		sb.append("\""+aAttributeValue+"\">");
		String indenting = indent ? "\n\t" : "";
		if(!aContent.equals(""))
			sb.append(indenting + aContent);
		sb.append(indenting + ("</"+aName+">").toLowerCase());
		if(indent) sb.append(indenting);
		return sb.toString();
	}
	
	/**
	 * Tests generate() method with a
	 * HTMLNode that has a child.
	 * <node_name Attribute_Name="Attribute_Value">Node_Content
	 * <child_node_name child_Attribute_Name="child_Attribute_Value">child_Node_Content</child_node_name>
	 * </node_name>
	 */
	public void testGenerate_HTMLNode_withChild() {
		HTMLNode methodHTMLNode = new HTMLNode(SAMPLE_OKAY_NODE_NAME_NON_EMPTY,
				SAMPLE_OKAY_ATTRIBUTE_NAME,SAMPLE_ATTRIBUTE_VALUE,
				SAMPLE_NODE_CONTENT);
		HTMLNode methodHTMLNodeChild = new HTMLNode(SAMPLE_OKAY_NODE_NAME_NON_EMPTY,
				SAMPLE_OKAY_ATTRIBUTE_NAME,SAMPLE_ATTRIBUTE_VALUE,
				SAMPLE_NODE_CONTENT);
		
		methodHTMLNode.addChild(methodHTMLNodeChild);
		
		assertEquals(("<"+SAMPLE_OKAY_NODE_NAME_NON_EMPTY+" ").toLowerCase() + 
				SAMPLE_OKAY_ATTRIBUTE_NAME + "=" +
				 "\""+SAMPLE_ATTRIBUTE_VALUE+"\">" +
				 SAMPLE_NODE_CONTENT +
				 
				 //child
				 generateFullNodeOutput(SAMPLE_OKAY_NODE_NAME_NON_EMPTY,
						 SAMPLE_OKAY_ATTRIBUTE_NAME, SAMPLE_ATTRIBUTE_VALUE, 
							SAMPLE_NODE_CONTENT) +
				 
				 ("</"+SAMPLE_OKAY_NODE_NAME_NON_EMPTY+">").toLowerCase(),
				 methodHTMLNode.generate());
	}
	
	/**
	 * Tests generate() method with a
	 * HTMLNode that has "%" as name.
	 * The expected output is just the HTMLNode content
	 */
	public void testGenerate_fromHTMLNode_percentName() {
		HTMLNode methodHTMLNode = new HTMLNode("%",
				SAMPLE_OKAY_ATTRIBUTE_NAME,SAMPLE_ATTRIBUTE_VALUE,
				SAMPLE_NODE_CONTENT);
		assertEquals(SAMPLE_NODE_CONTENT,
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
		methodHTMLNodeDoc.addChild(SAMPLE_OKAY_NODE_NAME_EMPTY);
		String generatedString = methodHTMLNodeDoc.generate();
		//consider only the HTMLDocType generated text
		assertEquals("<!DOCTYPE "+sampleDocType+" PUBLIC \""+sampleSystemUri+"\">", 	
				readFirstLine(generatedString));
		
	}

}
