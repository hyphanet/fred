package freenet.clients.http;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import freenet.clients.http.updateableelements.BaseUpdateableElement;
import freenet.clients.http.updateableelements.PushDataManager;
import freenet.support.Ticker;

/** This manager object will push elements at a fixed interval */
public class IntervalPusherManager {

	/** The interval when the elements will be pushed */
	private static final int			REFRESH_PERIOD	= 10000;

	/** The PushDataManager object */
	private final PushDataManager		pushDataManager;

	/** The Ticker to schedule the interval */
	private final Ticker				ticker;

	/** The job, that will refresh the elements */
	private Runnable					refresherJob	= 
		new Runnable() {
		
		public void run() {
			// Updating
			for (BaseUpdateableElement element : elements) {
				pushDataManager.updateElement(element.getUpdaterId(null));
			}
			
			// If there are more elements, it reschedules
			if (elements.size() > 0) {
				ticker.queueTimedJob(this, "Stats refresher", REFRESH_PERIOD, false, true);
			}
		}
	};
	
	/** The elements that are pushed at a fixed interval */
	private List<BaseUpdateableElement>	elements		= new CopyOnWriteArrayList<BaseUpdateableElement>();

	/**
	 * Constructor
	 * 
	 * @param ticker
	 *            - The Ticker
	 * @param pushDataManager
	 *            - The PushDataManager
	 */
	public IntervalPusherManager(Ticker ticker, PushDataManager pushDataManager) {
		this.ticker = ticker;
		this.pushDataManager = pushDataManager;
	}

	/**
	 * Registers an element to be pushed at a fixed interval
	 * 
	 * @param element
	 *            - The element
	 */
	public void registerUpdateableElement(BaseUpdateableElement element) {
		boolean needsStart = false;
		if (elements.size() == 0) {
			needsStart = true;
		}
		elements.add(element);
		// If this is the first element, then it starts the ticker
		if (needsStart) {
			ticker.queueTimedJob(refresherJob, "Stats refresher", REFRESH_PERIOD, false, true);
		}
	}

	/**
	 * Removes the element from interval pushing
	 * 
	 * @param element
	 *            - The element to be removed
	 */
	public void deregisterUpdateableElement(BaseUpdateableElement element) {
		elements.remove(element);
	}

}
