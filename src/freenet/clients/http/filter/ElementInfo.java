package freenet.clients.http.filter;

public class ElementInfo {

	private static final String[] VOID_ELEMENTS = { "area", "base", "basefont",
		"bgsound", "br", "col", "command", "embed", "event-source",
		"frame", "hr", "img", "input", "keygen", "link", "meta", "param",
		"source", "spacer", "wbr"};
	
	public static boolean isVoidElement(String element) {
		
		for(int i=0;i<VOID_ELEMENTS.length;i++)
		{
			if(VOID_ELEMENTS[i].equals(element))
				return true;
		}
		return false;
	}

}
