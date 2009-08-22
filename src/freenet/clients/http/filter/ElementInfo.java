package freenet.clients.http.filter;
import java.util.HashSet;
public class ElementInfo {

	private static HashSet<String> VOID_ELEMENTS=new HashSet<String>();
	static {
		VOID_ELEMENTS.add("area");
		VOID_ELEMENTS.add("base");
		VOID_ELEMENTS.add("basefont");
		VOID_ELEMENTS.add("bgsound");
		VOID_ELEMENTS.add("br");
		VOID_ELEMENTS.add("col");
		VOID_ELEMENTS.add("command");
		VOID_ELEMENTS.add("embed");
		VOID_ELEMENTS.add("event-source");
		VOID_ELEMENTS.add("frame");
		VOID_ELEMENTS.add("hr");
		VOID_ELEMENTS.add("img");
		VOID_ELEMENTS.add("input");
		VOID_ELEMENTS.add("keygen");
		VOID_ELEMENTS.add("link");
		VOID_ELEMENTS.add("meta");
		VOID_ELEMENTS.add("param");
		VOID_ELEMENTS.add("source");
		VOID_ELEMENTS.add("spacer");
		VOID_ELEMENTS.add("wbr");
		
	}
	public static boolean isVoidElement(String element) {
		
		return VOID_ELEMENTS.contains(element);
	}
	
	/** These elements are frequently used one after the other, and are invalid inside each other.
	 * AFAICS only <li>. */
	public static boolean tryAutoClose(String element) {
		if("li".equals(element)) return true;
		return false;
	}

}
