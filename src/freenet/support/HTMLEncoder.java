package freenet.support;

/**
 * Originally from com.websiteasp.ox pasckage.
 * 
 * Author: Yves Lempereur
 */
public class HTMLEncoder {

	public static String encode(String s) {
		int n = s.length();
		StringBuffer sb = new StringBuffer(n);
		for (int i = 0; i < n; i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' :
					sb.append("&quot;");
					break;
				case '&' :
					sb.append("&amp;");
					break;
					//				case '\'':
					//					sb.append("&apos;");
					//					break;
				case '<' :
					sb.append("&lt;");
					break;
				case '>' :
					sb.append("&gt;");
					break;
				default :
					sb.append(c);
					break;
			}
		}
		return sb.toString();
	}

}
