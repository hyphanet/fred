package freenet.client.dynamics;

import java.util.ArrayList;
import java.util.List;

import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.NodeList;
import com.google.gwt.user.client.Timer;
import com.google.gwt.user.client.ui.RootPanel;

import freenet.client.tools.TimeUtil;

public class TimeIncrementer implements IDynamic{
	
	@Override
	public void start() {

		new Timer() {
			@Override
			public void run() {
				NodeList<Element> list=RootPanel.getBodyElement().getElementsByTagName("span");
				for(int i=0;i<list.getLength();i++){
					Element e=list.getItem(i);
					String classAttr=e.getAttribute("class");
					if(classAttr!=null && classAttr.compareTo("needsIncrement")==0){
						Element inputElement=e.getElementsByTagName("input").getItem(0);
						int current=Integer.parseInt(inputElement.getAttribute("value"));
						current+=1000;
						inputElement.setAttribute("value", ""+current);
						e.getElementsByTagName("span").getItem(1).setInnerText(TimeUtil.formatTime(current, 2));
					}
					if(classAttr!=null && classAttr.compareTo("needsDecrement")==0){
						Element inputElement=e.getElementsByTagName("input").getItem(0);
						int current=Integer.parseInt(inputElement.getAttribute("value"));
						if(current>=1000){
							current-=1000;
						}
						inputElement.setAttribute("value", ""+current);
						e.getElementsByTagName("span").getItem(1).setInnerText(TimeUtil.formatTime(current, 2));
					}
				}
			}
		}.scheduleRepeating(1000);
	}
	

}
