package freenet.client.updaters;

import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.NodeList;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.ui.RootPanel;

public class ConnectionsListUpdater extends ReplacerUpdater {

	@Override
	public void updated(String elementId, String content) {
		super.updated(elementId, content);
		NodeList<Element> inputs=RootPanel.get(elementId).getElement().getElementsByTagName("input");
		for(int i=0;i<inputs.getLength();i++){
			Element e=inputs.getItem(i);
			if(e.getAttribute("name").compareTo("pageTitle")==0){
				Window.setTitle(e.getAttribute("value"));
			}
		}
	}

}
