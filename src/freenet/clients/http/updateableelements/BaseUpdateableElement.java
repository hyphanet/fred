package freenet.clients.http.updateableelements;

import freenet.clients.http.SimpleToadletServer;
import freenet.clients.http.ToadletContext;
import freenet.support.HTMLNode;

public abstract class BaseUpdateableElement extends HTMLNode {

	ToadletContext	ctx;

	public BaseUpdateableElement(String name, String requestUniqueName, ToadletContext ctx) {
		this(name, new String[] {}, new String[] {}, ctx);
	}

	public BaseUpdateableElement(String name, String attributeName, String attributeValue, ToadletContext ctx) {
		this(name, new String[] { attributeName }, new String[] { attributeValue }, ctx);
	}

	public BaseUpdateableElement(String name, String[] attributeNames, String[] attributeValues, ToadletContext ctx) {
		super(name, attributeNames, attributeValues);
		this.ctx = ctx;
	}

	protected void init() {
		// We set the id to easily find the element
		addAttribute("id", getUpdaterId());
		updateState();
		((SimpleToadletServer) ctx.getContainer()).pushDataManager.elementRendered(ctx.getUniqueId(), this);
	}

	public abstract void updateState();

	public abstract String getUpdaterId();

	public abstract String getUpdaterType();
	
	public abstract void dispose();
}
