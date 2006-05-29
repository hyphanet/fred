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
			UserAlert[] a = getAlerts();
			for(int i=0;i<a.length;i++) {
				UserAlert alert = a[i];
				if(!alert.isValid()) continue;
				
				short level = a[i].getPriorityClass();
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
				buf.append(a[i].getTitle());
				buf.append("</div>\n");
				//
				buf.append("<div class=\"infobox-content\">\n");
				buf.append(a[i].getText());
				//
				if(a[i].userCanDismiss())
					buf.append("<form method=\"post\" action=\".\"><input type=\"hidden\" name=\"disable\" value=\""+
							a[i].hashCode()+"\" /><input type=\"submit\" value=\""+a[i].dismissButtonText()+"\" /></form>");
				//
				buf.append("</div>\n");
				buf.append("</div>\n");
			}
		}
	}
	
}
