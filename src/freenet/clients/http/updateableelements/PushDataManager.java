package freenet.clients.http.updateableelements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import freenet.support.HTMLNode;
import freenet.support.Logger;

public class PushDataManager {

	private List<UpdateEvent>							awaitingNotifications	= new ArrayList<UpdateEvent>();

	private Map<String, List<BaseUpdateableElement>>	pages					= new HashMap<String, List<BaseUpdateableElement>>();

	private Map<String, List<String>>					elements				= new HashMap<String, List<String>>();

	public void updateElement(String id) {
		boolean needsUpdate = false;
		synchronized (awaitingNotifications) {
			if(elements.containsKey(id))for (String reqId : elements.get(id)) {
				awaitingNotifications.add(new UpdateEvent(reqId, id));
				needsUpdate = true;
			}
			if (needsUpdate) {
				awaitingNotifications.notifyAll();
			}
		}
	}

	public void elementRendered(String requestUniqueId, BaseUpdateableElement element) {
		if (pages.containsKey(requestUniqueId) == false) {
			pages.put(requestUniqueId, new ArrayList<BaseUpdateableElement>());
		}
		pages.get(requestUniqueId).add(element);
		if (elements.containsKey(element.getUpdaterId()) == false) {
			elements.put(element.getUpdaterId(), new ArrayList<String>());
		}
		elements.get(element.getUpdaterId()).add(requestUniqueId);
	}

	public HTMLNode getRenderedElement(String requestId, String id) {
		if (pages.get(requestId) != null) for (BaseUpdateableElement element : pages.get(requestId)) {
			if (element.getUpdaterId().compareTo(id) == 0) {
				element.updateState();
				return element;
			}
		}
		return null;
	}

	public UpdateEvent getNextNotification() {
		synchronized (awaitingNotifications) {
			while (awaitingNotifications.size() == 0) {
				try {
					awaitingNotifications.wait();
				} catch (InterruptedException ie) {
					return null;
				}
			}
			return awaitingNotifications.remove(0);
		}
	}

	public class UpdateEvent {
		private String	requestId;
		private String	elementId;

		private UpdateEvent(String requestId, String elementId) {
			this.requestId = requestId;
			this.elementId = elementId;
		}

		public String getRequestId() {
			return requestId;
		}

		public String getElementId() {
			return elementId;
		}
	}
}
