package freenet.client.updaters;

import freenet.client.messages.MessageManager;

public class XmlAlertUpdater extends ReplacerUpdater {

	@Override
	public void updated(String elementId, String content) {
		super.updated(elementId, content);
		MessageManager.get().updateMessages();
	}
}
