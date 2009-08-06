package freenet.client.updaters;

import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.NodeList;
import com.google.gwt.user.client.ui.RootPanel;

import freenet.client.messages.Message;
import freenet.client.messages.MessageManager;
import freenet.client.messages.Priority;

public class ImageElementUpdater extends ReplacerUpdater {

	public ImageElementUpdater() {
		int[] counted = countImageProgress();
		lastCounted = counted;
		if (counted[1] != 0) {
			Message newMsg = makeProgressMsg(counted[0], counted[1]);
			MessageManager.get().addMessage(newMsg);
			lastMessage = newMsg;
		}
	}

	private Message	lastMessage	= makeProgressMsg(0, 0);

	private int[]	lastCounted;

	@Override
	public void updated(String elementId, String content) {
		int[] previousFetched = new int[2];
		if (MessageManager.get().isMessagePresent(lastMessage)) {
			previousFetched = getProgressForElement(RootPanel.get(elementId).getElement());
		}
		super.updated(elementId, content);
		if (MessageManager.get().isMessagePresent(lastMessage)) {
			int[] counted = getProgressForElement(RootPanel.get(elementId).getElement());
			int[] nowCounted = new int[2];
			nowCounted[0] = lastCounted[0] - previousFetched[0] + counted[0];
			nowCounted[1] = lastCounted[1] - previousFetched[1] + counted[1];
			lastCounted = nowCounted;
			if (nowCounted[1] != 0) {
				Message newMsg = makeProgressMsg(nowCounted[0], nowCounted[1]);
				if (lastMessage != null) {
					MessageManager.get().replaceMessageAtPosition(MessageManager.get().getMessagePosition(lastMessage), newMsg);
				} else {
					MessageManager.get().addMessage(newMsg);
				}
				lastMessage = newMsg;
			}
		}
	}

	private Message makeProgressMsg(int fetched, int total) {
		return new Message("Image Progress:" + fetched + "/" + total, Priority.MINOR, null);// LOC
	}

	private int[] getProgressForElement(Element image) {
		int total = 0;
		int fetched = 0;
		NodeList<Element> inputs = image.getElementsByTagName("input");
		for (int j = 0; j < inputs.getLength(); j++) {
			Element input = inputs.getItem(j);
			if (input.getAttribute("name").compareTo("fetchedBlocks") == 0) {
				fetched = Integer.parseInt(input.getAttribute("value"));
			} else if (input.getAttribute("name").compareTo("requiredBlocks") == 0) {
				total = Integer.parseInt(input.getAttribute("value"));
			}
		}
		return new int[] { fetched, total };
	}

	private int[] countImageProgress() {
		int fetched = 0;
		int total = 0;
		NodeList<Element> elements = Document.get().getElementsByTagName("span");
		for (int i = 0; i < elements.getLength(); i++) {
			Element e = elements.getItem(i);
			if (e.getClassName().contains("ImageElement")) {
				int[] status = getProgressForElement(e);
				fetched += status[0];
				total += status[1];
			}
		}
		return new int[] { fetched, total };
	}
}
