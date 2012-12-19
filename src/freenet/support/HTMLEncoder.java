/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import java.util.HashMap;
import java.util.Map;

/**
 * Encodes any character mentioned with a substitute in the HTML spec. This
 * includes nulls, <>, quotes, but not control characters. It should be 
 * safe to put the output of this function into a web page; if it is not 
 * then we have big problems. Because we encode quotes it should also be 
 * safe to include it inside attributes. I am not certain where the list in
 * HTMLEntities came from, but the list of potentially markup-significant
 * characters in [X]HTML is *really* small.
 * 
 * Originally from com.websiteasp.ox pasckage.
 * 
 * @author avian (Yves Lempereur)
 * @author Unique Person@w3nO30p4p9L81xKTXbCaQBOvUww (via Frost)
 */
public class HTMLEncoder {
	public final static CharTable charTable = 
		new CharTable(HTMLEntities.encodeMap);
	
	public static String encode(String s) {
		int n = s.length();
		StringBuilder sb = new StringBuilder(n);
		encodeToBuffer(n, s, sb);
		return sb.toString();
	}

	public static void encodeToBuffer(String s, StringBuilder sb) {
		encodeToBuffer(s.length(), s, sb);
	}
	
	private static void encodeToBuffer(int n, String s, StringBuilder sb) {
		for (int i = 0; i < n; i++) {
			char c = s.charAt(i);
			String entity;
			if(Character.isLetterOrDigit(c)){ //only special characters need checking
				sb.append(c);
			} else if((entity = charTable.get(c))!=null){
                sb.append('&');
                sb.append(entity);
                sb.append(';');
			} else{
				sb.append(c);
		}
		}
		
	}

	/**
	 * Encode String so it is safe to be used in XML attribute value and text.
	 * 
	 * HTMLEncode.encode() use some HTML-specific entities (e.g. &amp;) hence not suitable for
	 * generic XML.
	 */
	public static String encodeXML(String s) {
		// Extensible Markup Language (XML) 1.0 (Fifth Edition)
		// [10]   	AttValue	   ::=   	'"' ([^<&"] | Reference)* '"'
		// 								|   "'" ([^<&'] | Reference)* "'"
		// [14]   	CharData	   ::=   	[^<&]* - ([^<&]* ']]>' [^<&]*)
		s = s.replace("&", "&#38;");

		s = s.replace("\"", "&#34;");
		s = s.replace("'", "&#39;");

		s = s.replace("<", "&#60;");
		s = s.replace(">", "&#62;"); // CharData can't contain ']]>'

		return s;
	}
		
	private final static class CharTable{
		private char[] chars;
		private String[] strings;
		private int modulo = 0;
		
		public CharTable(HashMap<Character, String> map){
			int[] keys = new int[map.size()]; 
			int keyIndex = 0;
			
			int max = 0;
			for (Character key : map.keySet()) {
				int val = key.charValue();
				keys[keyIndex++] = val;
				if(val > max) max = val;
			}
			
			modulo = map.size();
			int[] collisionTable = new int[max+1]; //using integers instead of booleans (no cleanup)
			boolean ok=false;
			while (!ok) {
			    ++modulo; //try a higher modulo
			    ok = true;
			    for (int i = 0; ok && i < keys.length; ++i){
			    	keyIndex = keys[i]%modulo; //try this modulo
			    	if (collisionTable[keyIndex] == modulo){ //is this value already used
			    		ok = false;
			    	}
			    	else{
			    		collisionTable[keyIndex] = modulo;
					}
			    }
			}
			//System.out.println("The modulo is:" + modulo); //was The modulo is:1474
			
			chars = new char[modulo];
			strings = new String[modulo];
			for (Map.Entry<Character,String> entry : map.entrySet()) {
				Character character = entry.getKey();
				keyIndex = character.charValue()%modulo;
				chars[keyIndex] = character.charValue();
				strings[keyIndex] = entry.getValue();
			}
			if (chars[0] == 0 && strings[0] != null) chars[0] = 1;
		}
		
		public String get(char key){
			return chars[key%modulo] == key? strings[key%modulo]:null;
		}
	}
}
