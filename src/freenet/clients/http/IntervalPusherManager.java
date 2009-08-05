package freenet.clients.http;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import freenet.clients.http.updateableelements.BaseUpdateableElement;
import freenet.clients.http.updateableelements.PushDataManager;
import freenet.node.Ticker;

public class IntervalPusherManager {

	private static final int REFRESH_PERIOD=10000;
	
	private final PushDataManager pushDataManager;
	
	private final Ticker ticker;
	
	private Runnable refresherJob=new Runnable() {
		
		public void run() {
			for(BaseUpdateableElement element:elements){
				pushDataManager.updateElement(element.getUpdaterId(null));				
			}
			
			if(elements.size()>0){
				ticker.queueTimedJob(this,"Stats refresher", REFRESH_PERIOD, false, true);
			}
		}
	};
	
	private List<BaseUpdateableElement> elements=new CopyOnWriteArrayList<BaseUpdateableElement>();
	
	public IntervalPusherManager(Ticker ticker,PushDataManager pushDataManager){
		this.ticker=ticker;
		this.pushDataManager=pushDataManager;
	}
	
	public void registerUpdateableElement(BaseUpdateableElement element){
		boolean needsStart=false;
		if(elements.size()==0){
			needsStart=true;
		}
		elements.add(element);
		if(needsStart){
			ticker.queueTimedJob(refresherJob,"Stats refresher", REFRESH_PERIOD, false, true);
		}
	}
	
	public void deregisterUpdateableElement(BaseUpdateableElement element){
		elements.remove(element);
	}

}
