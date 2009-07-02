package freenet.clients.http.updateableelements;

import freenet.clients.http.SimpleToadletServer;
import freenet.clients.http.ToadletContext;
import freenet.support.HTMLNode;

/** This abstract Node is the ancestor of all pushed elements. */
public abstract class BaseUpdateableElement extends HTMLNode {

	/** The context of the request */
	ToadletContext	ctx;

	public BaseUpdateableElement(String name, ToadletContext ctx) {
		this(name, new String[] {}, new String[] {}, ctx);
	}

	public BaseUpdateableElement(String name, String attributeName, String attributeValue, ToadletContext ctx) {
		this(name, new String[] { attributeName }, new String[] { attributeValue }, ctx);
	}

	public BaseUpdateableElement(String name, String[] attributeNames, String[] attributeValues, ToadletContext ctx) {
		super(name, attributeNames, attributeValues);
		this.ctx = ctx;
	}

	/** Initializes the Node. It needs to be invoked from the constructor */
	protected void init() {
		// We set the id to easily find the element
		addAttribute("id", getUpdaterId(ctx.getUniqueId()));
		// Updates the state, so the resulting page will have the actual state and content
		updateState();
		// Notifies the manager that the element has been rendered
		((SimpleToadletServer) ctx.getContainer()).pushDataManager.elementRendered(ctx.getUniqueId(), this);
	}

	/** Updates the state of the Node. The children should be removed and recreated. */
	public abstract void updateState();

	/** Returns the id, that identifies the element. It can depend on the request, but it might not use it. */
	public abstract String getUpdaterId(String requestId);

	/** Returns the type of the client-side updater. */
	public abstract String getUpdaterType();

	/** Disposes the Node */
	public abstract void dispose();
}
