package freenet.client.updaters;

import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.NodeList;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.ui.RootPanel;

/** An Updater for the connections list, that replaces the element and also replaces the page's title */
public class ConnectionsListUpdater extends ReplacerUpdater {

	@Override
	public void updated(String elementId, String content) {
		// Replaces the content
		super.updated(elementId, content);
		// Finds the hidden input and sets the title to it's value
		NodeList<Element> inputs = RootPanel.get(elementId).getElement().getElementsByTagName("input");
		for (int i = 0; i < inputs.getLength(); i++) {
			Element e = inputs.getItem(i);
			if (e.getAttribute("name").compareTo("pageTitle") == 0) {
				Window.setTitle(e.getAttribute("value"));
				RootPanel htmlTitlePanel=RootPanel.get("topbar");
				if(htmlTitlePanel!=null){
					if(htmlTitlePanel.getElement().getElementsByTagName("h1").getLength()==1){
						htmlTitlePanel.getElement().getElementsByTagName("h1").getItem(0).setInnerHTML(e.getAttribute("value"));
					}
				}
			}
		}
	}

}
