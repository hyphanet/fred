package freenet.client.updaters;

import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.NodeList;
import com.google.gwt.user.client.ui.RootPanel;

import freenet.client.l10n.L10n;
import freenet.client.messages.Message;
import freenet.client.messages.MessageManager;
import freenet.client.messages.Priority;
import freenet.client.FreenetJs;

/** An Updater that replaces the element and refreshes the image loading's overall progress message */
public class ImageElementUpdater extends ReplacerUpdater {

	public ImageElementUpdater() {
		int[] counted = countImageProgress();
		lastCounted = counted;
		if (counted[1] != 0) {
			// Only show the messages if image loading is in progress
			Message newMsg = makeProgressMsg(counted[0], counted[1]);
			MessageManager.get().addMessage(newMsg);
			lastMessage = newMsg;
		}
	}

	/** The last message, that is currently showed */
	private Message	lastMessage	= makeProgressMsg(0, 0);

	/** The last result of progress counting */
	private int[]	lastCounted;

	@Override
	public void updated(String elementId, String content) {
		// Saves the progress of the old image element
		int[] previousFetched = new int[2];
		if (MessageManager.get().isMessagePresent(lastMessage)) {
			previousFetched = getProgressForElement(RootPanel.get(elementId).getElement());
		}
		// Replace the content
		super.updated(elementId, content);
		// If message is shown
		if (MessageManager.get().isMessagePresent(lastMessage)) {
			// Gets the progress
			int[] counted = getProgressForElement(RootPanel.get(elementId).getElement());
			int[] nowCounted = new int[2];
			nowCounted[0] = lastCounted[0] - previousFetched[0] + counted[0];
			nowCounted[1] = lastCounted[1] - previousFetched[1] + counted[1];
			// Update the overall progress
			lastCounted = nowCounted;
			// Replace the message, if not complete yet
			if (nowCounted[1] != 0) {
				Message newMsg = makeProgressMsg(nowCounted[0], nowCounted[1]);
				if (lastMessage != null) {
					MessageManager.get().replaceMessageAtPosition(MessageManager.get().getMessagePosition(lastMessage), newMsg);
				} else {
					MessageManager.get().addMessage(newMsg);
				}
				lastMessage = newMsg;
			}else if(lastMessage!=null){
				MessageManager.get().removeMessage(lastMessage);
			}
		}
	}

	/**
	 * Creates the progress message for the given progress
	 * 
	 * @param fetched
	 *            - The number of the fetched blocks
	 * @param total
	 *            - The number of the total blocks
	 * @return The message to be shown in the messages panel
	 */
	private Message makeProgressMsg(int fetched, int total) {
		return new Message(L10n.get("imageprogress") + fetched + "/" + total, Priority.MINOR, null,true);
	}

	/**
	 * Returns the progress for a given element
	 * 
	 * @param image
	 *            - The image element
	 * @return [0]:the number of fetched blocks [1]:the total number of blocks
	 */
	private int[] getProgressForElement(Element image) {
		int total = 0;
		int fetched = 0;
		// Finds the hidden inputs
		NodeList<Element> inputs = image.getElementsByTagName("input");
		for (int j = 0; j < inputs.getLength(); j++) {
			Element input = inputs.getItem(j);
			// Gets the data if the corresponding input is found
			if (input.getAttribute("name").compareTo("fetchedBlocks") == 0) {
				try {
					fetched = Integer.parseInt(input.getAttribute("value"));
				} catch (NumberFormatException e) {
					FreenetJs.log("fetchedBlocks value \""+input.getAttribute("value")+"\" is invalid: "+e);
				}
			} else if (input.getAttribute("name").compareTo("requiredBlocks") == 0) {
				try {
					total = Integer.parseInt(input.getAttribute("value"));
				} catch (NumberFormatException e) {
					FreenetJs.log("requiredBlocks value \""+input.getAttribute("value")+"\" is invalid: "+e);
				}
			}
		}
		return new int[] { fetched, total };
	}

	/**
	 * Returns the overall progress for all images in the page
	 * 
	 * @return [0]:the number of fetched blocks [1]:the total number of blocks
	 */
	private int[] countImageProgress() {
		int fetched = 0;
		int total = 0;
		// Cycle through all 'span' elements
		NodeList<Element> elements = Document.get().getElementsByTagName("span");
		for (int i = 0; i < elements.getLength(); i++) {
			Element e = elements.getItem(i);
			// Checks if it is an ImageElement
			if (e.getClassName().contains("ImageElement")) {
				// Add it's progress to the overall
				int[] status = getProgressForElement(e);
				fetched += status[0];
				total += status[1];
			}
		}
		return new int[] { fetched, total };
	}
}
