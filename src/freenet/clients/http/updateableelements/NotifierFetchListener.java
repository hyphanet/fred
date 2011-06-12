package freenet.clients.http.updateableelements;

import freenet.clients.http.FProxyFetchListener;

/** This listener notifies the PushDataManager when a download make some progress */
public class NotifierFetchListener implements FProxyFetchListener {

	private PushDataManager			pushManager;

	private BaseUpdateableElement	element;

	public NotifierFetchListener(PushDataManager pushManager, BaseUpdateableElement element) {
		this.pushManager = pushManager;
		this.element = element;
	}

	@Override
	public void onEvent() {
		pushManager.updateElement(element.getUpdaterId(null));
	}

	@Override
	public String toString() {
		return "NotifierFetchListener[pushManager:" + pushManager + ",element;" + element + "]";
	}
}
