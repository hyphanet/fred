package freenet.node.useralerts;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;

/**
 * Collection of UserAlert's.
 */
public class UserAlertManager implements Comparator {

	final HashSet alerts;
	
	public UserAlertManager() {
		alerts = new HashSet();
	}
	
	public synchronized void register(UserAlert alert) {
		alerts.add(alert);
	}
	
	public synchronized void unregister(UserAlert alert) {
		alerts.remove(alert);
	}

	public UserAlert[] getAlerts() {
		UserAlert[] a = (UserAlert[]) alerts.toArray(new UserAlert[alerts.size()]);
		Arrays.sort(a, this);
		return a;
	}

	public int compare(Object arg0, Object arg1) {
		UserAlert a0 = (UserAlert)arg0;
		UserAlert a1 = (UserAlert)arg1;
		if(a0 == a1) return 0;
		short p0 = a0.getPriorityClass();
		short p1 = a1.getPriorityClass();
		if(p0 < p1) return -1;
		if(p0 > p1) return 1;
		return 0;
	}

	/**
	 * Write the alerts as HTML to a StringBuffer
	 */
	public void toHtml(StringBuffer buf) {
		synchronized(alerts){
			UserAlert[] alerts = getAlerts();
			for(int i=0;i<alerts.length;i++) {
				UserAlert alert = alerts[i];
				if(!alert.isValid()) continue;
				
				short level = alert.getPriorityClass();
				if(level <= UserAlert.CRITICAL_ERROR)
					buf.append("<div class=\"infobox infobox-error\">\n");
				else if(level <= UserAlert.ERROR)
					buf.append("<div class=\"infobox infobox-alert\">\n");
				else if(level <= UserAlert.WARNING)
					buf.append("<div class=\"infobox infobox-warning\">\n");
				else if(level <= UserAlert.MINOR)
					buf.append("<div class=\"infobox infobox-information\">\n");
				//
				buf.append("<div class=\"infobox-header\">\n");
				buf.append(alert.getTitle());
				buf.append("</div>\n");
				//
				buf.append("<div class=\"infobox-content\">\n");
				buf.append(alert.getText());
				//
				if(alert.userCanDismiss())
					buf.append("<form method=\"post\" action=\".\"><input type=\"hidden\" name=\"disable\" value=\""+
							alert.hashCode()+"\" /><input type=\"submit\" value=\""+alert.dismissButtonText()+"\" /></form>");
				//
				buf.append("</div>\n");
				buf.append("</div>\n");
			}
		}
	}

	/**
	 * Write the alert summary as HTML to a StringBuffer
	 */
	public void toSummaryHtml(StringBuffer buf) {
	  short highestLevel = 99;
	  int numberOfCriticalError = 0;
	  int numberOfError = 0;
	  int numberOfWarning = 0;
	  int numberOfMinor = 0;
	  int totalNumber = 0;
		synchronized(alerts){
			UserAlert[] alerts = getAlerts();
			for(int i=0;i<alerts.length;i++) {
				UserAlert alert = alerts[i];
				if(!alert.isValid()) continue;
				short level = alert.getPriorityClass();
				if(level < highestLevel)
					highestLevel = level;
				if(level <= UserAlert.CRITICAL_ERROR)
					numberOfCriticalError += 1;
				else if(level <= UserAlert.ERROR)
					numberOfError += 1;
				else if(level <= UserAlert.WARNING)
					numberOfWarning += 1;
				else if(level <= UserAlert.MINOR)
					numberOfMinor += 1;
				totalNumber += 1;
			}
			if(totalNumber == 0)
				return;
			boolean separatorNeeded = false;
			StringBuffer alertSummaryString = new StringBuffer(1024);
			if (numberOfCriticalError != 0) {
				alertSummaryString.append("Critical Error: " + numberOfCriticalError);
				separatorNeeded = true;
			}
			if (numberOfError != 0) {
				if (separatorNeeded)
					alertSummaryString.append(" | ");
				alertSummaryString.append("Error: " + numberOfError);
				separatorNeeded = true;
			}
			if (numberOfWarning != 0) {
				if (separatorNeeded)
					alertSummaryString.append(" | ");
				alertSummaryString.append("Warning: " + numberOfWarning);
				separatorNeeded = true;
			}
			if (numberOfMinor != 0) {
				if (separatorNeeded)
					alertSummaryString.append(" | ");
				alertSummaryString.append("Minor: " + numberOfMinor);
				separatorNeeded = true;
			}
      if (separatorNeeded)
        alertSummaryString.append(" | ");
      alertSummaryString.append("Total: " + totalNumber);
      if(highestLevel <= UserAlert.CRITICAL_ERROR)
        buf.append("<div class=\"infobox infobox-error\">\n");
      else if(highestLevel <= UserAlert.ERROR)
        buf.append("<div class=\"infobox infobox-alert\">\n");
      else if(highestLevel <= UserAlert.WARNING)
        buf.append("<div class=\"infobox infobox-warning\">\n");
      else if(highestLevel <= UserAlert.MINOR)
        buf.append("<div class=\"infobox infobox-information\">\n");
      buf.append("<div class=\"infobox-header\">\n");
      buf.append("Outstanding Alerts");
      buf.append("</div>\n");
      buf.append("<div class=\"infobox-content\">\n");
      buf.append(alertSummaryString);
      buf.append("</div>\n");
      buf.append("</div>\n");
		}
	}
	
}
