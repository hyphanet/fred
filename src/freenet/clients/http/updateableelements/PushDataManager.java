package freenet.clients.http.updateableelements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class PushDataManager {

	private List<UpdateEvent>							awaitingNotifications	= new ArrayList<UpdateEvent>();

	private Map<String, List<BaseUpdateableElement>>	pages					= new HashMap<String, List<BaseUpdateableElement>>();

	private Map<String, List<String>>					elements				= new HashMap<String, List<String>>();

	private Map<String, Long>							lastReceivedKeepalive	= new HashMap<String, Long>();

	private final Lock									elementLock				= new ReentrantLock();

	private Timer										cleaner					= new Timer(true);

	public PushDataManager() {
		int delayInMs = (int) (UpdaterConstants.KEEPALIVE_INTERVAL_SECONDS * 1000 * 2.1);
		cleaner.schedule(new CleanerTimerTask(), delayInMs, delayInMs);
	}

	public void updateElement(String id) {
		boolean needsUpdate = false;
		synchronized (awaitingNotifications) {
			if (elements.containsKey(id)) for (String reqId : elements.get(id)) {
				awaitingNotifications.add(new UpdateEvent(reqId, id));
				needsUpdate = true;
			}
			if (needsUpdate) {
				awaitingNotifications.notifyAll();
			}
		}
	}

	public void elementRendered(String requestUniqueId, BaseUpdateableElement element) {
		elementLock.lock();
		try {
			if (pages.containsKey(requestUniqueId) == false) {
				pages.put(requestUniqueId, new ArrayList<BaseUpdateableElement>());
			}
			pages.get(requestUniqueId).add(element);
			if (elements.containsKey(element.getUpdaterId()) == false) {
				elements.put(element.getUpdaterId(), new ArrayList<String>());
			}
			elements.get(element.getUpdaterId()).add(requestUniqueId);
		} finally {
			elementLock.unlock();
		}
	}

	public BaseUpdateableElement getRenderedElement(String requestId, String id) {
		elementLock.lock();
		try {
			if (pages.get(requestId) != null) for (BaseUpdateableElement element : pages.get(requestId)) {
				if (element.getUpdaterId().compareTo(id) == 0) {
					element.updateState();
					return element;
				}
			}
			return null;
		} finally {
			elementLock.unlock();
		}
	}

	public boolean keepAliveReceived(String requestId) {
		elementLock.lock();
		try{
			if(lastReceivedKeepalive.containsKey(requestId)==false){
				return false;
			}
			lastReceivedKeepalive.put(requestId,System.currentTimeMillis());
			return true;
		}finally{
			elementLock.unlock();
		}
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

	private class CleanerTimerTask extends TimerTask {
		@Override
		public void run() {
			// TODO Auto-generated method stub

		}
	}
}
