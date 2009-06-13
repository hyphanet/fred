package freenet.client.updaters;

import com.google.gwt.user.client.ui.RootPanel;

import freenet.client.FreenetJs;

public class ReplacerUpdater implements IUpdater {

	@Override
	public void updated(String elementId, String content) {
		RootPanel.get(elementId).getElement().setInnerHTML(content);
	}

}
