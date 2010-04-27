package freenet.client.messages;

import java.util.ArrayList;
import java.util.List;

import com.google.gwt.dom.client.Element;
import com.google.gwt.event.dom.client.MouseDownEvent;
import com.google.gwt.event.dom.client.MouseDownHandler;
import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.Response;
import com.google.gwt.user.client.ui.Anchor;
import com.google.gwt.user.client.ui.HasHorizontalAlignment;
import com.google.gwt.user.client.ui.HorizontalPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.RootPanel;
import com.google.gwt.user.client.ui.VerticalPanel;

import freenet.client.FreenetJs;
import freenet.client.UpdaterConstants;
import freenet.client.l10n.L10n;
import freenet.client.tools.FreenetRequest;
import freenet.client.tools.QueryParameter;

/** This manager singleton class manages the message panel in the page */
public class MessageManager {
	/** The singleton instance */
	private static MessageManager	instance	= null;

	/** Returns the singleton instance */
	public static MessageManager get() {
		if (instance == null) {
			instance = new MessageManager();
		}
		return instance;
	}

	/** The messages that are currently displayed */
	private List<Message>	messages		= new ArrayList<Message>();

	/** The panel where messages are displayed */
	private VerticalPanel	messagesPanel	= new VerticalPanel();

	private MessageManager() {
		// Initializes the messages panel and places it to the page
		messagesPanel.getElement().setId("messagesPanel");
		messagesPanel.setHorizontalAlignment(HasHorizontalAlignment.ALIGN_CENTER);
		messagesPanel.getElement().getStyle().setProperty("position", "fixed");
		messagesPanel.getElement().getStyle().setProperty("top", "0px");
		messagesPanel.getElement().getStyle().setProperty("width", "100%");
		messagesPanel.getElement().setId("messagesPanel");
		RootPanel.get().add(messagesPanel);
		// Updates the messages
		updateMessages();
	}

	/**
	 * Adds a message to the panel
	 * 
	 * @param msg
	 *            - The message to add
	 */
	public void addMessage(Message msg) {
		//Disabled showing fproxy messages. This is TEMPORARY!
		if(msg.getAnchor()!=null){
			return;
		}
		messages.add(msg);
		redrawMessages();
	}

	/**
	 * Removes a message at a given position
	 * 
	 * @param position
	 *            - The position of the message that is removed
	 */
	public void removeMessage(int position) {
		messages.remove(position);
		redrawMessages();
	}

	/**
	 * Removes a message
	 * 
	 * @param message
	 *            - The message that will be removed
	 */
	public void removeMessage(Message message) {
		messages.remove(message);
		redrawMessages();
	}

	/**
	 * Gets the position of a message
	 * 
	 * @param msg
	 *            - The message which position will be returned
	 * @return The position of the message
	 */
	public int getMessagePosition(Message msg) {
		return messages.indexOf(msg);
	}

	/**
	 * Replaces a message with a new one at a given position
	 * 
	 * @param position
	 *            - The position, which will be replaced
	 * @param msg
	 *            - The message that will replace the original
	 */
	public void replaceMessageAtPosition(int position, Message msg) {
		messages.remove(position);
		messages.add(position, msg);
		redrawMessages();
	}

	/**
	 * Checks if a message is currently shown
	 * 
	 * @param msg
	 *            - The message to search for
	 * @return Whether the message is present
	 */
	public boolean isMessagePresent(Message msg) {
		return messages.contains(msg);
	}

	/** Redraw the messages panel */
	private void redrawMessages() {
		// Clear it first
		messagesPanel.clear();
		FreenetJs.log("REDRAWING MESSAGES");
		messagesPanel.getElement().getStyle().setProperty("background", "white");
		// Cycle through the messages
		for (int i = 0; i < messages.size(); i++) {
			final Message m = messages.get(i);
			FreenetJs.log("REDRAWING MESSAGE:" + m.getMsg());
			// The panel which will hold the message
			HorizontalPanel hpanel = new HorizontalPanel();
			// Sets the background color based on the priority
			switch (m.getPriority()) {
				case MINOR:
					hpanel.getElement().getStyle().setProperty("background", "green");
					break;
				case WARNING:
					hpanel.getElement().getStyle().setProperty("background", "yellow");
					break;
				case ERROR:
					hpanel.getElement().getStyle().setProperty("background", "orange");
					break;
				case CRITICAL:
					hpanel.getElement().getStyle().setProperty("background", "red");
					break;
			}
			// Sets some css properties
			hpanel.getElement().getStyle().setProperty("width", "100%");
			hpanel.getElement().getStyle().setProperty("height", "100%");
			hpanel.getElement().getStyle().setProperty("display", "block");
			hpanel.getElement().getStyle().setPropertyPx("padding", 3);

			// The short description label
			Label msgLabel = new Label(m.getMsg());
			hpanel.add(msgLabel);
			msgLabel.getElement().getParentElement().getStyle().setProperty("border", "none");
			if (m.getAnchor() != null) {
				Anchor showElement = new Anchor(L10n.get("show"), "/alerts/#" + m.getAnchor());
				hpanel.add(showElement);
				showElement.getElement().getParentElement().getStyle().setProperty("border", "none");
			}

			if (m.isCanDismiss()) {
				// The hide link, it will hide the message if clicked on
				Anchor hideElement = new Anchor(L10n.get("hide"));
				hideElement.addMouseDownHandler(new MouseDownHandler() {
					@Override
					public void onMouseDown(MouseDownEvent event) {
						// Only send a request if the message is originated from the server
						if (m.getAnchor() != null) {
							FreenetRequest.sendRequest(UpdaterConstants.dismissAlertPath, new QueryParameter("anchor", m.getAnchor()), new RequestCallback() {
								@Override
								public void onResponseReceived(Request request, Response response) {
									// When a response is got, the server is already removed the message. We can remove it too safely
									removeMessage(m);
								}

								@Override
								public void onError(Request request, Throwable exception) {
									// Don't do anything. If the server removed the message, it will push the change, if not, the user will try again
								}
							});
						} else {
							// If it is originated from the client, then simply hide it
							messages.remove(m);
							redrawMessages();
						}
					}
				});
				hpanel.add(hideElement);
				hideElement.getElement().getParentElement().getStyle().setProperty("border", "none");
			}

			// Adds the message to the panel
			messagesPanel.add(hpanel);
		}
	}

	public void updateMessages() {
		// If an XmlAlertElement is present, then refresh the messages
		if (RootPanel.get("alerts") != null) {
			// Remove all server originated messages
			for (Message m : new ArrayList<Message>(messages)) {
				if (m.getAnchor() != null) {
					removeMessage(m);
				}
			}
			// Redraw the messages from the XML
			for (int i = 0; i < RootPanel.get("alerts").getElement().getElementsByTagName("alert").getLength(); i++) {
				Element alert = RootPanel.get("alerts").getElement().getElementsByTagName("alert").getItem(i);
				String anchor = alert.getElementsByTagName("anchor").getItem(0).getInnerText();
				Priority priority = null;
				switch (Integer.parseInt(alert.getElementsByTagName("priority").getItem(0).getInnerText())) {
					case 0:
						priority = Priority.CRITICAL;
						break;
					case 1:
						priority = Priority.ERROR;
						break;
					case 2:
						priority = Priority.WARNING;
						break;
					case 3:
						priority = Priority.MINOR;
						break;
				}
				String title = alert.getElementsByTagName("alertTitle").getItem(0).getInnerText();
				addMessage(new Message(title, priority, anchor, Boolean.parseBoolean(alert.getElementsByTagName("canDismiss").getItem(0).getInnerText())));
			}
		}
	}

}
