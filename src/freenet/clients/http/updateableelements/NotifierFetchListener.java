package freenet.clients.http.updateableelements;

import freenet.clients.http.FProxyFetchListener;
import freenet.clients.http.FProxyFetchWaiter;

/** This listener notifies the PushDataManager when a download make some progress */
public class NotifierFetchListener implements FProxyFetchListener {

	private PushDataManager			pushManager;

	private BaseUpdateableElement	element;

	public NotifierFetchListener(PushDataManager pushManager, BaseUpdateableElement element) {
		this.pushManager = pushManager;
		this.element = element;
	}

	public void onEvent() {
		if(element instanceof ImageElement){
			ImageElement img=(ImageElement)element;
			FProxyFetchWaiter fw=img.tracker.getFetcher(img.key, img.maxSize);
			if(fw!=null && fw.getResult().hasData()){
				System.err.println("Image dl complete for url:"+((ImageElement)element).key.toString());
			}
		}
		pushManager.updateElement(element.getUpdaterId(null));
	}
}
