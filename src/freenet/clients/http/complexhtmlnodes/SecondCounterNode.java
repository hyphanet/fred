package freenet.clients.http.complexhtmlnodes;

import freenet.support.HTMLNode;
import freenet.support.TimeUtil;

public class SecondCounterNode extends HTMLNode {
	public SecondCounterNode(long initialValue,boolean ascending,String text){
		super("span","class", ascending?"needsIncrement":"needsDecrement");
		addChild("input",new String[]{"type","value"},new String[]{"hidden",""+initialValue});
		addChild("span",text);
		addChild("span",TimeUtil.formatTime(initialValue));
	}
}
