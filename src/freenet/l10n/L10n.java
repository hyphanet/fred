/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.l10n;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.MissingResourceException;

import freenet.clients.http.TranslationToadlet;
import freenet.support.HTMLEncoder;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

/**
 * This class provides a trivial internationalization framework to a freenet node.
 * 
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 * 
 * TODO: Maybe we ought to use the locale to set the default language.
 * TODO: Maybe base64 the override file ?
 * TODO: Add support for "custom", unknown languages ?
 * 
 * comment(mario): for www interface we might detect locale from http requests?
 * for other access (telnet) using system locale would probably be good, but
 * it would be nice to have a command to switch locale on the fly.
 */
public class L10n {
	public static final String CLASS_NAME = "L10n";
	public static final String PREFIX = "freenet.l10n.";
	public static final String SUFFIX = ".properties";
	public static final String OVERRIDE_SUFFIX = ".override" + SUFFIX;
	
	// English has to remain the first one!
	public static final String[] AVAILABLE_LANGUAGES = { "en", "fr", "pl", "it", "se", "unlisted" };
	private final String selectedLanguage;
	
	private static SimpleFieldSet currentTranslation = null;
	private static SimpleFieldSet fallbackTranslation = null;
	private static L10n currentClass = null;
	
	private static SimpleFieldSet translationOverride;
	private static final Object sync = new Object();

	L10n(String selected) {
		selectedLanguage = selected;
		File tmpFile = new File(L10n.PREFIX + selected + L10n.OVERRIDE_SUFFIX);
		
		try {
			if(tmpFile.exists() && tmpFile.canRead()) {
				Logger.normal(this, "Override file detected : let's try to load it");
				translationOverride = SimpleFieldSet.readFrom(tmpFile, false, false);
			} else
				translationOverride = null;
			
		} catch (IOException e) {
			translationOverride = null;
			Logger.error(this, "IOError while accessing the file!" + e.getMessage(), e);
		}
		currentTranslation = loadTranslation(selectedLanguage);
		if(currentTranslation == null) {
			Logger.error(this, "The translation file for " + selectedLanguage + " is invalid. The node will load an empty template.");
			currentTranslation = null;
			translationOverride = new SimpleFieldSet(false);
		}
	}
	
	/**
	 * Set the default language used by the framework.
	 * 
	 * @param selectedLanguage (2 letter code)
	 * @throws MissingResourceException
	 */
	public static void setLanguage(String selectedLanguage) throws MissingResourceException {
		synchronized (sync) {
			for(int i=0; i<AVAILABLE_LANGUAGES.length; i++){
				if(selectedLanguage.equalsIgnoreCase(AVAILABLE_LANGUAGES[i])){		
					selectedLanguage = AVAILABLE_LANGUAGES[i];
					Logger.normal(CLASS_NAME, "Changing the current language to : " + selectedLanguage);

					currentClass = new L10n(selectedLanguage);	

					if(currentTranslation == null) {
						currentClass = new L10n(AVAILABLE_LANGUAGES[0]);	
						throw new MissingResourceException("Unable to load the translation file for "+selectedLanguage, "l10n", selectedLanguage);
					}

					return;
				}
			}

			currentClass = new L10n(AVAILABLE_LANGUAGES[0]);
			Logger.error(CLASS_NAME, "The requested translation is not available!" + selectedLanguage);
			throw new MissingResourceException("The requested translation ("+selectedLanguage+") hasn't been found!", CLASS_NAME, selectedLanguage);
		}
	}
	
	public static void setOverride(String key, String value) {
		key = key.trim();
		value = value.trim();
		synchronized (sync) {
			// Is the override already declared ? if not, create it.
			if(translationOverride == null)
				translationOverride = new SimpleFieldSet(false);
			
			// If there is no need to keep it in the override, remove it...
			// unless the original/default is the same as the translation
			if(("".equals(value) || L10n.getString(key).equals(value)) && !L10n.getDefaultString(key).equals(value)) {
				translationOverride.removeValue(key);
			} else {
				value = value.replaceAll("(\r|\n|\t)+", "");
				
				// Set the value of the override
				translationOverride.putOverwrite(key, value);
				Logger.normal("L10n", "Got a new translation key: set the Override!");
			}

			// Save the file to disk
			_saveTranslationFile();
		}
	}
	
	private static void _saveTranslationFile() {
		FileOutputStream fos = null;
		BufferedOutputStream bos = null;
		File finalFile = new File(L10n.PREFIX + L10n.getSelectedLanguage() + L10n.OVERRIDE_SUFFIX);
		
		try {
			// We don't set deleteOnExit on it : if the save operation fails, we want a backup
			File tempFile = new File(finalFile.getPath() + "-" + System.currentTimeMillis() + ".tmp");
			Logger.minor("L10n", "The temporary filename is : " + tempFile);
			
			fos = new FileOutputStream(tempFile);
			bos = new BufferedOutputStream(fos);
			
			bos.write(L10n.translationOverride.toOrderedString().getBytes("UTF-8"));
			bos.flush();
			
			
			tempFile.renameTo(finalFile);
			tempFile.delete();
			
			Logger.normal("L10n", "Override file saved successfully!");
		} catch (IOException e) {
			Logger.error("L10n", "Error while saving the translation override: "+ e.getMessage(), e);
		} finally {
			try {
				if(bos != null) bos.close();
				if(fos != null) fos.close();
			} catch (IOException e) {}
		}
	}
	
	/**
	 * Return a new copy of the current translation file 
	 * 
	 * @return SimpleFieldSet or null
	 */
	public static SimpleFieldSet getCurrentLanguageTranslation() {
		synchronized (sync) {
			return (currentTranslation == null ? null : new SimpleFieldSet(currentTranslation));	
		}
	}
	
	/**
	 * Return a copy of the current translation override if it exists or null
	 * 
	 * @return SimpleFieldSet or null
	 */
	public static SimpleFieldSet getOverrideForCurrentLanguageTranslation() {
		synchronized (sync) {
			return (translationOverride == null ? null : new SimpleFieldSet(translationOverride));	
		}
	}
	
	/**
	 * Return a copy of the default translation file (english one)
	 * 
	 * @return SimpleFieldSet
	 */
	public static SimpleFieldSet getDefaultLanguageTranslation() {
		synchronized (sync) {
			if(fallbackTranslation == null)
				fallbackTranslation = loadTranslation(AVAILABLE_LANGUAGES[0]);
				
			return new SimpleFieldSet(fallbackTranslation);	
		}
	}
	
	/**
	 * The real meat
	 * 
	 * Same thing as getString(key, false);
	 * Ensure it will *always* return a String value.
	 * 
	 * @param key
	 * @return the translated string or the default value from the default language or the key if nothing is found
	 */
	public static String getString(String key) {
		return getString(key, false);
	}
	
	/**
	 * You probably don't want to use that one directly
	 * @see getString(String)
	 */
	public static String getString(String key, boolean returnNullIfNotFound) {
		String result = null;
		synchronized (sync) {
			if(translationOverride != null)
				result = translationOverride.get(key);
		}
		if(result != null) return result;
		 
		synchronized (sync) {
			if(currentTranslation != null)
				result = currentTranslation.get(key);	
		}
		if(result != null)
			return result;
		else
			return (returnNullIfNotFound ? null : getDefaultString(key));
	}
	
	/**
	 * Almost the same as getString(String) ... but it returns a HTMLNode and gives the user the ability
	 *  to contribute to the translation though the translation toadlet
	 * 
	 * @param key
	 * @return HTMLNode
	 */
	public static HTMLNode getHTMLNode(String key) {
		String value = getString(key, true);
		if(value != null)
			return new HTMLNode("#", value);
		HTMLNode translationField = new HTMLNode("span", "class", "translate_it") ;
		translationField.addChild("#", getDefaultString(key));
		translationField.addChild("a", "href", TranslationToadlet.TOADLET_URL+"?translate=" + key).addChild("small", " (translate it in your native language!)");
			
		return translationField;
	}
	
	/**
	 * Return the english translation of the key or the key itself if it doesn't exist.
	 * 
	 * @param key
	 * @return String
	 */
	public static String getDefaultString(String key) {
		String result = null;
		// We instanciate it only if necessary
		synchronized (sync) {
			if(fallbackTranslation == null)
				fallbackTranslation = loadTranslation(AVAILABLE_LANGUAGES[0]);
			
			result = fallbackTranslation.get(key);	
		}
		
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
				result = result.replaceAll("\\$\\{"+patterns[i]+"\\}", quoteReplacement(values[i]));
		
		return result;
	}
	
	private static String quoteReplacement(String s) {
		if ((s.indexOf('\\') == -1) && (s.indexOf('$') == -1))
			return s;
		StringBuffer sb = new StringBuffer();
		for (int i=0; i<s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\') {
				sb.append('\\');
				sb.append('\\');
			} else if (c == '$') {
				sb.append('\\');
				sb.append('$');
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}
	
	/**
	 * Return the ISO code of the language used by the framework
	 * 
	 * @return String
	 */
	public static String getSelectedLanguage() {
		synchronized (sync) {
			return currentClass.selectedLanguage;	
		}
	}
	
	/**
	 * Load a translation file depending on the given name and using the prefix
	 * 
	 * @param name
	 * @return the Properties object or null if not found
	 */
	public static SimpleFieldSet loadTranslation(String name) {
        name = PREFIX.replace ('.', '/').concat(PREFIX.concat(name.concat(SUFFIX)));
        
        SimpleFieldSet result = null;
        InputStream in = null;
        try {
        	ClassLoader loader = ClassLoader.getSystemClassLoader();
        	
        	// Returns null on lookup failures:
        	in = loader.getResourceAsStream(name);
        	if(in != null)
        		result = SimpleFieldSet.readFrom(in, false, false);
        } catch (Exception e) {
        	Logger.error("L10n", "Error while loading the l10n file from " + name + " :" + e.getMessage());
            result = null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignore) {}
        }
        
        return result;
    }

	public static boolean isOverridden(String key) {
		synchronized(sync) {
			if(translationOverride == null) return false;
			return translationOverride.get(key) != null; 
		}
	}
	
	/**
	 * Add a localised string with some raw HTML substitutions.
	 * Useful when some part of a sentence needs to be bold e.g.
	 * @param key The L10n key.
	 * @param patterns The strings to search for.
	 * @param values The strings to substitute in.
	 */
	public static void addL10nSubstitution(HTMLNode node, String key, String[] patterns, String[] values) {
		String result = HTMLEncoder.encode(getString(key));
		assert(patterns.length == values.length);
		for(int i=0; i<patterns.length; i++)
			result = result.replaceAll("\\$\\{"+patterns[i]+"\\}", quoteReplacement(values[i]));
		node.addChild("%", result);
	}

}
