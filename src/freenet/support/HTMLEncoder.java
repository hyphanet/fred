/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import java.util.HashMap;
import java.util.Iterator;

/**
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
		StringBuffer sb = new StringBuffer(n);
		encodeToBuffer(n, s, sb);
		return sb.toString();
	}

	public static void encodeToBuffer(String s, StringBuffer sb) {
		encodeToBuffer(s.length(), s, sb);
	}
	
	private static void encodeToBuffer(int n, String s, StringBuffer sb) {
		for (int i = 0; i < n; i++) {
			char c = s.charAt(i);
			if(Character.isLetterOrDigit(c)){ //only special characters need checking
				sb.append(c);
			} else if(charTable.containsKey(c)){
                sb.append('&');
                sb.append(charTable.get(c));
                sb.append(';');
			} else{
				sb.append(c);
		}
		}
		
	}
		
	private final static class CharTable{
		private char[] chars;
		private String[] strings;
		private int modulo = 0;
		
		public CharTable(HashMap map){
			int[] keys = new int[map.size()]; 
			int keyIndex = 0;
			
			int max = 0;
			for(Iterator it = map.keySet().iterator();it.hasNext(); keyIndex++){
				int val = (int) ((Character)it.next()).charValue();
				keys[keyIndex] = val;
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
			Character character;
			for(Iterator it = map.keySet().iterator();it.hasNext(); keyIndex++){
				character = ((Character)it.next());
				keyIndex = character.charValue()%modulo;
				chars[keyIndex] = character.charValue();
				strings[keyIndex] = (String) map.get(character);
			}
			if (chars[0] == 0 && strings[0] != null) chars[0] = 1;
		}
		
		public boolean containsKey(char key){
			return chars[key%modulo] == key;
		}
		
		public String get(char key){
			return chars[key%modulo] == key? strings[key%modulo]:null;
		}
	}
}
