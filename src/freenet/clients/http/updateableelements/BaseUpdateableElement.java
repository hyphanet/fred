package freenet.clients.http.updateableelements;

import freenet.support.HTMLNode;

public abstract class BaseUpdateableElement extends HTMLNode {
	
	public BaseUpdateableElement(String name,String requestUniqueName) {
		this(name, new String[]{}, new String[]{},requestUniqueName);
	}
	
	public BaseUpdateableElement(String name,String attributeName,String attributeValue,String requestUniqueName){
		this(name,new String[]{attributeName},new String[]{attributeValue},requestUniqueName);
	}

	public BaseUpdateableElement(String name, String[] attributeNames, String[] attributeValues,String requestUniqueName) {
		super(name, attributeNames, attributeValues);
	}
	
	protected void init(){
		// We set the id to easily find the element
		addAttribute("id", getUpdaterId());
		updateState();
		
		
	}
	
	public abstract void updateState();
	
	public abstract String getUpdaterId();
}
