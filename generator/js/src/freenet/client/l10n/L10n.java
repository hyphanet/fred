package freenet.client.l10n;

import com.google.gwt.i18n.client.Dictionary;

/** This static class handles the l10n */
public class L10n {

	/** It fills a dictionary */
	private static Dictionary	dict	= Dictionary.getDictionary("l10n");

	/**
	 * Returns the localized value for a key
	 * 
	 * @param key
	 *            - The localization key
	 * @return The localized value
	 */
	public static String get(String key) {
		try{
			return dict.get(key);
		}catch(Exception mre){
			return "#"+key+"#";
		}
	}

}
