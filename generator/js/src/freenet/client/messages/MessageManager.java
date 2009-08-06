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
import freenet.client.update.DefaultUpdateManager;
import freenet.client.update.UpdateListener;

public class MessageManager implements UpdateListener {
	private static MessageManager	instance	= null;

	public static MessageManager get() {
		if (instance == null) {
			instance = new MessageManager();
		}
		return instance;
	}

	private List<Message>	messages		= new ArrayList<Message>();

	private VerticalPanel	messagesPanel	= new VerticalPanel();

	private MessageManager() {
		messagesPanel.getElement().setId("messagesPanel");
		messagesPanel.setHorizontalAlignment(HasHorizontalAlignment.ALIGN_CENTER);
		messagesPanel.getElement().getStyle().setProperty("position", "fixed");
		messagesPanel.getElement().getStyle().setProperty("top", "0px");
		messagesPanel.getElement().getStyle().setProperty("width", "100%");
		RootPanel.get().add(messagesPanel);
		DefaultUpdateManager.registerListener(this);
		onUpdate();
	}

	public void addMessage(Message msg) {
		messages.add(msg);
		redrawMessages();
	}

	public void removeMessage(int position) {
		messages.remove(position);
		redrawMessages();
	}

	public void removeMessage(Message message) {
		messages.remove(message);
		redrawMessages();
	}
	
	public int getMessagePosition(Message msg){
		return messages.indexOf(msg);
	}
	
	public void replaceMessageAtPosition(int position,Message msg){
		messages.remove(position);
		messages.add(position, msg);
		redrawMessages();
	}
	
	public boolean isMessagePresent(Message msg){
		return messages.contains(msg);
	}

	private void redrawMessages() {
		messagesPanel.clear();
		FreenetJs.log("REDRAWING MESSAGES");
		switch (getMaxPriorityPresent()) {
			case MINOR:
				messagesPanel.getElement().getStyle().setProperty("background", "green");
				break;
			case WARNING:
				messagesPanel.getElement().getStyle().setProperty("background", "yellow");
				break;
			case ERROR:
				messagesPanel.getElement().getStyle().setProperty("background", "orange");
				break;
			case CRITICAL:
				messagesPanel.getElement().getStyle().setProperty("background", "red");
				break;
		}

		for (int i = 0; i < messages.size(); i++) {
			final Message m = messages.get(i);
			FreenetJs.log("REDRAWING MESSAGE:"+m.getMsg());
			HorizontalPanel hpanel = new HorizontalPanel();
			Label msgLabel = new Label(m.getMsg());
			hpanel.add(msgLabel);
			msgLabel.getElement().getParentElement().getStyle().setProperty("border", "none");
			Anchor hideElement = new Anchor(L10n.get("hide"));
			hideElement.addMouseDownHandler(new MouseDownHandler() {
				@Override
				public void onMouseDown(MouseDownEvent event) {
					if (m.getAnchor() != null) {
						FreenetRequest.sendRequest(UpdaterConstants.dismissAlertPath, new QueryParameter("anchor", m.getAnchor()),new RequestCallback() {
							@Override
							public void onResponseReceived(Request request, Response response) {
								removeMessage(m);
							}
							
							@Override
							public void onError(Request request, Throwable exception) {
							}
						});
					}else{
						messages.remove(m);
						redrawMessages();
					}
				}
			});
			hpanel.add(hideElement);
			hideElement.getElement().getParentElement().getStyle().setProperty("border", "none");

			messagesPanel.add(hpanel);
		}
	}

	private Priority getMaxPriorityPresent() {
		Priority currentMax = Priority.MINOR;
		for (Message m : messages) {
			if (m.getPriority().compareTo(currentMax) > 0) {
				currentMax = m.getPriority();
			}
		}
		return currentMax;
	}

	@Override
	public void onUpdate() {
		if (RootPanel.get("alerts") != null) {
			for (Message m : new ArrayList<Message>(messages)) {
				if (m.getAnchor() != null) {
					removeMessage(m);
				}
			}
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
				addMessage(new Message(title, priority, anchor));
			}
		}
	}

}
