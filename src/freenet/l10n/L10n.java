/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.l10n;

import java.io.InputStream;
import java.util.MissingResourceException;
import java.util.Properties;

import freenet.support.Logger;

/**
 * This class provides a trivial internationalization framework to a freenet node.
 * 
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 * 
 * TODO: Maybe we ought to use the locale to set the default language.
 */

public class L10n {
	private static final String CLASS_NAME = "L10n";
	private static String prefix = "freenet.l10n.";
	
	// English has to remain the first one!
	public static final String[] availableLanguages = { "en", "fr" };
	private String selectedLanguage = availableLanguages[0];
	
	private static Properties currentProperties = null;
	private static Properties fallbackProperties = null;
	private static L10n currentClass = null;

	L10n(String selected) {
		selectedLanguage = selected;
		currentProperties = loadProperties(selectedLanguage);
	}
	
	/**
	 * Set the default language used by the framework.
	 * 
	 * @param selectedLanguage (2 letter code)
	 * @throws MissingResourceException
	 */
	public static void setLanguage(String selectedLanguage) throws MissingResourceException {
		for(int i=0; i<availableLanguages.length; i++){
			if(selectedLanguage.equalsIgnoreCase(availableLanguages[i])){
				selectedLanguage = availableLanguages[i];
				Logger.normal(CLASS_NAME, "Changing the current language to : " + selectedLanguage);
				currentClass = new L10n(selectedLanguage);
				if(currentProperties == null)
					currentClass = new L10n(availableLanguages[0]);
				return;
			}
		}
		
		currentClass = new L10n(availableLanguages[0]);
		Logger.error(CLASS_NAME, "The requested translation is not available!" + selectedLanguage);
		throw new MissingResourceException("The requested translation ("+selectedLanguage+") hasn't been found!", CLASS_NAME, selectedLanguage);
	}
	
	/**
	 * The real meat
	 * 
	 * @param key
	 * @return the translated string or the default value from the default language or the key if nothing is found
	 */
	public static String getString(String key) {
		String result = currentProperties.getProperty(key);
		if(result != null) return result;
		
		// We instanciate it only if necessary
		if(fallbackProperties == null) fallbackProperties = loadProperties(availableLanguages[0]);
		result = fallbackProperties.getProperty(key);
		if(result != null) {
			Logger.normal(CLASS_NAME, "The translation for " + key + " hasn't been found! please tell the maintainer.");
			return result; 
		}
		Logger.error(CLASS_NAME, "The translation for " + key + " hasn't been found!");
		return key;
	}
	
	/**
	 * Allows things like :
	 * L10n.getString("testing.test", new String[]{ "test1", "test2" }, new String[] { "a", "b" })
	 * 
	 * @param key
	 * @param patterns : a list of patterns wich are matchable from the translation
	 * @param values : the values corresponding to the list
	 * @return the translated string or the default value from the default language or the key if nothing is found
	 */
	public static String getString(String key, String[] patterns, String[] values) {
		assert(patterns.length == values.length);
		String result = getString(key);

		for(int i=0; i<patterns.length; i++)
			result = result.replaceAll("\\$\\("+patterns[i]+"\\)", values[i]);
		
		return result;
	}
	
	/**
	 * Load a property file depending on the given name and using the prefix
	 * 
	 * @param name
	 * @return the Properties object or null if not found
	 */
	private static Properties loadProperties (String name) {
        name = prefix.replace ('.', '/').concat(prefix.concat(name.concat(".properties")));
            
        Properties result = null;
        InputStream in = null;
        try {
        	ClassLoader loader = ClassLoader.getSystemClassLoader();
        	
        	// Returns null on lookup failures:
        	in = loader.getResourceAsStream(name);
        	if(in != null) {
        		result = new Properties();
        		result.load(in); // Can throw IOException
        	}
        } catch (Exception e) {
            result = null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignore) {}
        }
        
        return result;
    }
	
	public static String getSelectedLanguage() {
		return currentClass.selectedLanguage;
	}
	
	public static void main(String[] args) {
		L10n.setLanguage("en");
		System.out.println(L10n.getString("testing.test"));
		L10n.setLanguage("fr");
		System.out.println(L10n.getString("testing.test"));
		System.out.println(L10n.getString("testing.test", new String[]{ "test1", "test2" }, new String[] { "a", "b" }));
	}
}
