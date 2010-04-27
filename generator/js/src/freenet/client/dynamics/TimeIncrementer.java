package freenet.client.dynamics;

import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.NodeList;
import com.google.gwt.user.client.Timer;
import com.google.gwt.user.client.ui.RootPanel;

import freenet.client.tools.TimeUtil;

/** This class increments/decrements the time counters in the page. */
public class TimeIncrementer implements IDynamic {

	@Override
	public void start() {
		// Starts a timer at a frequency of 1/sec
		new Timer() {
			@Override
			public void run() {
				// Cycle through all 'span' elements
				NodeList<Element> list = RootPanel.getBodyElement().getElementsByTagName("span");
				for (int i = 0; i < list.getLength(); i++) {
					Element e = list.getItem(i);
					String classAttr = e.getAttribute("class");
					// Checks if the element's class indicates that it needs to be incremented
					if (classAttr != null && (classAttr.compareTo("needsIncrement") == 0 || classAttr.compareTo("needsDecrement") == 0)) {
						Element inputElement = e.getElementsByTagName("input").getItem(0);
						// Reads the current time value from the hidden input
						int current = Integer.parseInt(inputElement.getAttribute("value"));
						if (classAttr.compareTo("needsIncrement") == 0) {
							// Increments the current timer
							current += 1000;
						} else if (current > 1000) {
							// Decrements only if it stays positive
							current -= 1000;
						}
						// Writes back to the hidden input
						inputElement.setAttribute("value", "" + current);
						// Changes the shown value
						e.getElementsByTagName("span").getItem(1).setInnerText(TimeUtil.formatTime(current, 2));
					}
				}
			}
		}.scheduleRepeating(1000);
	}

}
