package freenet.client.l10n;

import com.google.gwt.i18n.client.Dictionary;

public class L10n {
	
	private static Dictionary dict=Dictionary.getDictionary("l10n");
	
	public static String get(String key){
		return dict.get(key);
	}

}
