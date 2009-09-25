package freenet.clients.http.updateableelements;

import freenet.clients.http.SimpleToadletServer;
import freenet.clients.http.ToadletContainer;
import freenet.clients.http.ToadletContext;
import freenet.node.useralerts.UserEventListener;

/** Base class for the alert boxes */
public abstract class BaseAlertElement extends BaseUpdateableElement {

	public BaseAlertElement(String name, ToadletContext ctx) {
		this(name, new String[] {}, new String[] {}, ctx);
	}

	public BaseAlertElement(String name, String attributeName, String attributeValue, ToadletContext ctx) {
		this(name, new String[] { attributeName }, new String[] { attributeValue }, ctx);
	}

	public BaseAlertElement(String name, String[] attributeNames, String[] attributeValues, ToadletContext ctx) {
		super(name, attributeNames, attributeValues, ctx);
		listener = new UserEventListener() {
			public void alertsChanged() {
				ToadletContainer container = BaseAlertElement.this.ctx.getContainer();
				if(container == null) return;
				PushDataManager pushDataManager = ((SimpleToadletServer) container).pushDataManager;
				if(pushDataManager == null) return;
				pushDataManager.updateElement(getUpdaterId(null));
			}
		};
		((SimpleToadletServer) ctx.getContainer()).getCore().alerts.registerListener(listener);
	}

	private final UserEventListener	listener;

	@Override
	public void dispose() {
		((SimpleToadletServer) ctx.getContainer()).getCore().alerts.deregisterListener(listener);
	}

	@Override
	public String getUpdaterId(String requestId) {
		return getClass().getName();
	}

	@Override
	public String getUpdaterType() {
		return UpdaterConstants.REPLACER_UPDATER;
	}

}
