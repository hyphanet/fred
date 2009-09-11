/* This code is part of Freenet. It is distributed under the GNU General
* Public License, version 2 (or at your option any later version). See
* http://www.gnu.org/ for further details of the GPL. */
package freenet.l10n;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.MissingResourceException;

import freenet.clients.http.TranslationToadlet;
import freenet.support.HTMLEncoder;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;

/**
* This class provides a trivial internationalization framework to a Freenet node.
*
* @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
*
* TODO: Maybe base64 the override file ?
*
*/
public class L10n {
	public static final String CLASS_NAME = "L10n";
	
	/** @see "http://www.omniglot.com/language/names.htm" */
	public enum LANGUAGE {
		ENGLISH("en", "English", "eng"),
		SPANISH("es", "Español", "spa"),
		DANISH("da", "Dansk", "dan"),
		GERMAN("de", "Deutsch", "deu"),
		FINNISH("fi", "Suomi", "fin"),
		FRENCH("fr", "Français", "fra"),
		ITALIAN("it", "Italiano", "ita"),
		NORWEGIAN("no", "Norsk", "nor"),
		POLISH("pl", "Polski", "pol"),
		SWEDISH("se", "Svenska", "svk"),
	        CHINESE("zh-cn", "中文(简体)", "chn"),
		CHINESE_TAIWAN("zh-tw", "中文(繁體)", "zh-tw"),
		UNLISTED("unlisted", "unlisted", "unlisted");
		
		/** The identifier we use internally : MUST BE UNIQUE! */
		public final String shortCode;
		/** The identifier shown to the user */
		public final String fullName;
		/** The mapping with the installer's l10n (@see bug #2424); MUST BE UNIQUE! */
		public final String isoCode;
		
		public final String l10nFilename;
		public final String l10nOverrideFilename;
		
		private LANGUAGE(String shortCode, String fullName, String isoCode) {
			this.shortCode = shortCode;
			this.fullName = fullName;
			this.isoCode = isoCode;
			this.l10nFilename = "freenet/l10n/freenet.l10n."+shortCode+".properties";
			this.l10nOverrideFilename = "freenet.l10n."+shortCode+".override.properties";
		}

		LANGUAGE(LANGUAGE l) {
			this(l.shortCode, l.fullName, l.isoCode);
		}
		
		public static LANGUAGE mapToLanguage(String whatever) {
			for(LANGUAGE currentLanguage : LANGUAGE.values()) {
				if(currentLanguage.shortCode.equalsIgnoreCase(whatever) ||
				   currentLanguage.fullName.equalsIgnoreCase(whatever) ||
				   currentLanguage.isoCode.equalsIgnoreCase(whatever) ||
				   currentLanguage.toString().equalsIgnoreCase(whatever))
				{
					return currentLanguage;
				}
			}
			return null;
		}
		
		public static String[] valuesWithFullNames() {
			LANGUAGE[] allValues = values();
			String[] result = new String[allValues.length];
			for(int i=0; i<allValues.length; i++)
				result[i] = allValues[i].fullName;
			
			return result;
		}
		
		public static LANGUAGE getDefault() {
			return ENGLISH;
		}
	}
	
	private final LANGUAGE selectedLanguage;
	
	private static SimpleFieldSet currentTranslation = null;
	private static SimpleFieldSet fallbackTranslation = null;
	private static L10n currentClass = null;
	
	private static SimpleFieldSet translationOverride;
	private static final Object sync = new Object();

	L10n(LANGUAGE selected) {		
		selectedLanguage = selected;
		try {
			File tmpFile = new File(selected.l10nOverrideFilename);
			if(tmpFile.exists() && tmpFile.canRead() && tmpFile.length() > 0) {
				Logger.normal(this, "Override file detected : let's try to load it");
				translationOverride = SimpleFieldSet.readFrom(tmpFile, false, false);
			} else {
                                // try to restore a backup
                                File backup = new File(tmpFile.getParentFile(), tmpFile.getName()+".bak");
                                if(backup.exists() && backup.length() > 0) {
                                    Logger.normal(this, "Override-backup file detected : let's try to load it");
                                    translationOverride = SimpleFieldSet.readFrom(backup, false, false);
                                }
				translationOverride = null;
                        }
			
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
			Logger.normal(CLASS_NAME, "Changing the current language to : " + selectedLanguage);
			L10n oldClass = currentClass;
			LANGUAGE lang = LANGUAGE.mapToLanguage(selectedLanguage);
			if(lang == null) {
				currentClass = (oldClass != null ? oldClass : new L10n(LANGUAGE.getDefault()));
				Logger.error(CLASS_NAME, "The requested translation is not available!" + selectedLanguage);
				throw new MissingResourceException("The requested translation (" + selectedLanguage + ") hasn't been found!", CLASS_NAME, selectedLanguage);
			} else
				currentClass = new L10n(lang);
			
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
			if (("".equals(value)) || (value.equals(currentTranslation.get(key)))) {
				translationOverride.removeValue(key);
			} else {
				value = value.replaceAll("(\r|\n|\t)+", "");
				
				// Set the value of the override
				translationOverride.putOverwrite(key, value);
				Logger.normal(CLASS_NAME, "Got a new translation key: set the Override!");
			}

			// Save the file to disk
			_saveTranslationFile();
		}
	}
	
	private static void _saveTranslationFile() {
		FileOutputStream fos = null;
		File finalFile = new File(getSelectedLanguage().l10nOverrideFilename);
		
		try {
			// We don't set deleteOnExit on it : if the save operation fails, we want a backup
			// FIXME: REDFLAG: not symlink-race proof!
			File tempFile = new File(finalFile.getParentFile(), finalFile.getName()+".bak");
			Logger.minor(CLASS_NAME, "The temporary filename is : " + tempFile);
			
			fos = new FileOutputStream(tempFile);
                        L10n.translationOverride.writeTo(fos);
			
			FileUtil.renameTo(tempFile, finalFile);
			Logger.normal(CLASS_NAME, "Override file saved successfully!");
		} catch (IOException e) {
			Logger.error(CLASS_NAME, "Error while saving the translation override: "+ e.getMessage(), e);
		} finally {
			Closer.close(fos);
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
				fallbackTranslation = loadTranslation(LANGUAGE.getDefault());
				
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
	* @see #getString(String)
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
		else {
			Logger.normal(CLASS_NAME, "The translation for " + key + " hasn't been found ("+getSelectedLanguage()+")! please tell the maintainer.");
			return (returnNullIfNotFound ? null : getDefaultString(key));
		}
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
				fallbackTranslation = loadTranslation(LANGUAGE.getDefault());
			
			result = fallbackTranslation.get(key);	
		}
		
		if(result != null) {
			return result;
		}
		Logger.error(CLASS_NAME, "The default translation for " + key + " hasn't been found!");
		System.err.println("The default translation for " + key + " hasn't been found!");
		new Exception().printStackTrace();
		return key;
	}
	
	/**
	* Allows things like :
	* L10n.getString("testing.test", new String[]{ "test1", "test2" }, "a", "b" })
	*
	* @param key
	* @param patterns : a list of patterns which are matchable from the translation
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
		if (s == null)
			return "(null)";
		if ((s.indexOf('\\') == -1) && (s.indexOf('$') == -1))
			return s;
		StringBuilder sb = new StringBuilder();
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
	public static LANGUAGE getSelectedLanguage() {
		synchronized (sync) {
			if((currentClass == null) || (currentClass.selectedLanguage == null))
				return LANGUAGE.getDefault();
			else
				return currentClass.selectedLanguage;	
		}
	}
	
	/**
	* Load a translation file depending on the given name and using the prefix
	*
	* @param lang The chosen language.
	* @return the Properties object or null if not found
	*/
	public static SimpleFieldSet loadTranslation(LANGUAGE lang) {
		SimpleFieldSet result = null;
		InputStream in = null;
		try {
			ClassLoader loader = ClassLoader.getSystemClassLoader();
			
			// Returns null on lookup failures:
			in = loader.getResourceAsStream(lang.l10nFilename);
			if(in != null)
				result = SimpleFieldSet.readFrom(in, false, false);
		} catch (Exception e) {
			Logger.error(CLASS_NAME, "Error while loading the l10n file from " + lang.l10nFilename + " :" + e.getMessage(), e);
			result = null;
		} finally {
			Closer.close(in);
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

	public static String getString(String key, String pattern, String value) {
		return getString(key, new String[] { pattern }, new String[] { value }); // FIXME code efficiently!
	}

}
