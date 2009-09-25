package freenet.client.updaters;

import com.google.gwt.user.client.ui.RootPanel;

import freenet.client.FreenetJs;

/** This simple Updater is replaces the element's content with the new content */
public class ReplacerUpdater implements IUpdater {

	@Override
	public void updated(String elementId, String content) {
		FreenetJs.log("Replacing element id:" + elementId + " with content:" + content + " element:" + RootPanel.get(elementId));
		if (RootPanel.get(elementId) != null) {
			FreenetJs.log("element.getElement():" + RootPanel.get(elementId).getElement() + " current innerHTML:" + RootPanel.get(elementId).getElement().getInnerHTML());
		}
		try {
			// Finds the element and replaces it's content with the new one
			RootPanel.get(elementId).getElement().setInnerHTML(content);
		} catch (Exception e) {
			FreenetJs.log("Error when setting html" + e.toString());
		}
		try {
			FreenetJs.log("content after update:" + RootPanel.get(elementId).getElement().getInnerHTML());
		} catch (Exception e) {
			FreenetJs.log("Error logging content after update "+e);
		}
	}

}
