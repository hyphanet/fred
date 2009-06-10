package freenet.client.elemetupdaters;

import com.google.gwt.user.client.ui.RootPanel;

public class ReplacerUpdater implements IElementUpdater {

	@Override
	public void update(String id, String content) {
		RootPanel.get(id).getElement().setInnerHTML(content);
	}

}
