/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.l10n;

import freenet.clients.http.TranslationToadlet;
import freenet.support.HTMLEncoder;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.MissingResourceException;

/**
 * This is the core of all the localization stuff. This method can get
 * localized strings from any SimpleFieldSet file, and can, if necessary,
 * use a custom ClassLoader. The language can be changed at anytime.
 *
 * Note : do not use this class *as is*, use NodeL10n.getBase() or
 * PluginL10n.getBase().
 *
 * Note : this class also supports using/saving/editing overriden translations.
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 * @author Artefact2
 */
public class BaseL10n {

	/** @see "http://www.omniglot.com/language/names.htm" */
	public enum LANGUAGE {

		ENGLISH("en", "English", "eng"),
		SPANISH("es", "Español", "spa"),
		DANISH("da", "Dansk", "dan"),
		DUTCH("nl", "Nederlands", "nld"),
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

		private LANGUAGE(String shortCode, String fullName, String isoCode) {
			this.shortCode = shortCode;
			this.fullName = fullName;
			this.isoCode = isoCode;
		}

		LANGUAGE(LANGUAGE l) {
			this(l.shortCode, l.fullName, l.isoCode);
		}

		/**
		 * Create a new LANGUAGE object from either its short code, its full
		 * name or its ISO code.
		 * @param whatever Short code, full name or ISO code.
		 * @return LANGUAGE
		 */
		public static LANGUAGE mapToLanguage(String whatever) {
			for (LANGUAGE currentLanguage : LANGUAGE.values()) {
				if (currentLanguage.shortCode.equalsIgnoreCase(whatever) ||
						currentLanguage.fullName.equalsIgnoreCase(whatever) ||
						currentLanguage.isoCode.equalsIgnoreCase(whatever) ||
						currentLanguage.toString().equalsIgnoreCase(whatever)) {
					return currentLanguage;
				}
			}
			return null;
		}

		public static String[] valuesWithFullNames() {
			LANGUAGE[] allValues = values();
			String[] result = new String[allValues.length];
			for (int i = 0; i < allValues.length; i++) {
				result[i] = allValues[i].fullName;
			}

			return result;
		}

		public static LANGUAGE getDefault() {
			return ENGLISH;
		}
	}
	
	private LANGUAGE lang;
	private String l10nFilesBasePath;
	private String l10nFilesMask;
	private String l10nOverrideFilesMask;
	private SimpleFieldSet currentTranslation = null;
	private SimpleFieldSet fallbackTranslation = null;
	private SimpleFieldSet translationOverride;
	private ClassLoader cl;

	public BaseL10n(String l10nFilesBasePath, String l10nFilesMask, String l10nOverrideFilesMask) {
		this(l10nFilesBasePath, l10nFilesMask, l10nOverrideFilesMask, LANGUAGE.getDefault());
	}

	public BaseL10n(String l10nFilesBasePath, String l10nFilesMask, String l10nOverrideFilesMask, final LANGUAGE lang) {
		this(l10nFilesBasePath, l10nFilesMask, l10nOverrideFilesMask, lang, ClassLoader.getSystemClassLoader());
	}

	/**
	 * Create a new BaseL10n object.
	 *
	 * Note : you shouldn't have to run this yourself. Use PluginL10n or NodeL10n.
	 * @param l10nFilesBasePath Base path of the l10n files, ex. "com/mycorp/myproject/l10n"
	 * @param l10nFilesMask Mask of the l10n files, ex. "messages_${lang}.l10n"
	 * @param l10nOverrideFilesMask Same as l10nFilesMask, but for overriden messages.
	 * @param lang Language to use.
	 * @param cl ClassLoader to use.
	 */
	public BaseL10n(String l10nFilesBasePath, String l10nFilesMask, String l10nOverrideFilesMask, final LANGUAGE lang, final ClassLoader cl) {
		if (!l10nFilesBasePath.endsWith("/")) {
			l10nFilesBasePath += "/";
		}

		this.l10nFilesBasePath = l10nFilesBasePath;
		this.l10nFilesMask = l10nFilesMask;
		this.l10nOverrideFilesMask = l10nOverrideFilesMask;
		this.cl = cl;
		this.setLanguage(lang);
	}

	/**
	 * Get the full base name of the L10n file used by the current language.
	 * @return String
	 */
	public String getL10nFileName(LANGUAGE lang) {
		return this.l10nFilesBasePath + this.l10nFilesMask.replace("${lang}", lang.shortCode);
	}

	/**
	 * Get the full base name of the L10n override file used by the current language.
	 * @return String
	 */
	public String getL10nOverrideFileName(LANGUAGE lang) {
		return this.l10nOverrideFilesMask.replace("${lang}", lang.shortCode);
	}

	/**
	 * Use a new language, and load the SimpleFieldSets accordingly.
	 * @param selectedLanguage New language to use.
	 * @throws MissingResourceException If the l10n file could not be found.
	 */
	public void setLanguage(final LANGUAGE selectedLanguage) throws MissingResourceException {
		if (selectedLanguage == null) {
			throw new MissingResourceException("LANGUAGE given is null !", this.getClass().getName(), "");
		}

		this.lang = selectedLanguage;

		Logger.normal(this.getClass(), "Changing the current language to : " + this.lang);

		try {
			final File tmpFile = new File(this.getL10nOverrideFileName(this.lang));
			if (tmpFile.exists() && tmpFile.canRead() && tmpFile.length() > 0) {
				Logger.normal(this, "Override file detected : let's try to load it");
				this.translationOverride = SimpleFieldSet.readFrom(tmpFile, false, false);
			} else {
				// try to restore a backup
				final File backup = new File(tmpFile.getParentFile(), tmpFile.getName() + ".bak");
				if (backup.exists() && backup.length() > 0) {
					Logger.normal(this, "Override-backup file detected : let's try to load it");
					this.translationOverride = SimpleFieldSet.readFrom(backup, false, false);
				}
				this.translationOverride = null;
			}

		} catch (IOException e) {
			this.translationOverride = null;
			Logger.error(this, "IOError while accessing the file!" + e.getMessage(), e);
		}

		this.currentTranslation = this.loadTranslation(lang);
		if (this.currentTranslation == null) {
			Logger.error(this, "The translation file for " + lang + " is invalid. The node will load an empty template.");
			this.currentTranslation = null;
			this.translationOverride = new SimpleFieldSet(false);
		}
	}

	/**
	 * Load the l10n file for a custom language and return its parsed SimpleFieldSet.
	 * @param lang Language to use.
	 * @return SimpleFieldSet
	 */
	private SimpleFieldSet loadTranslation(LANGUAGE lang) {
		SimpleFieldSet result = null;
		InputStream in = null;

		try {
			// Returns null on lookup failures:
			in = this.cl.getResourceAsStream(this.getL10nFileName(lang));
			if (in != null) {
				result = SimpleFieldSet.readFrom(in, false, false);
			} else {
				Logger.error(this.getClass(), "Could not get resource : " + this.getL10nFileName(lang));
			}
		} catch (Exception e) {
			Logger.error(this.getClass(), "Error while loading the l10n file from " + this.getL10nFileName(lang) + " :" + e.getMessage(), e);
			result = null;
		} finally {
			Closer.close(in);
		}

		return result;
	}

	/**
	 * Load the fallback translation. Synchronized.
	 */
	private synchronized void loadFallback() {
		if (this.fallbackTranslation == null) {
			this.fallbackTranslation = loadTranslation(LANGUAGE.getDefault());
		}
	}

	/**
	 * Get the language currently used by this BaseL10n.
	 * @return LANGUAGE
	 */
	public LANGUAGE getSelectedLanguage() {
		return this.lang;
	}

	/**
	 * Returns true if a key is overriden.
	 * @param key Key to check override status
	 * @return boolean
	 */
	public boolean isOverridden(String key) {
		if (this.translationOverride == null) {
			return false;
		}
		return this.translationOverride.get(key) != null;
	}

	/**
	 * Override a custom key with a new value.
	 * @param key Key to override.
	 * @param value New value of that key.
	 */
	public void setOverride(String key, String value) {
		key = key.trim();
		value = value.trim();
		// Is the override already declared ? if not, create it.
		if (this.translationOverride == null) {
			this.translationOverride = new SimpleFieldSet(false);
		}

		// If there is no need to keep it in the override, remove it...
		// unless the original/default is the same as the translation
		if ("".equals(value) || value.equals(this.currentTranslation.get(key))) {
			this.translationOverride.removeValue(key);
		} else {
			value = value.replaceAll("(\r|\n|\t)+", "");

			// Set the value of the override
			this.translationOverride.putOverwrite(key, value);
			Logger.normal(this.getClass(), "Got a new translation key: set the Override!");
		}

		// Save the file to disk
		saveTranslationFile();
	}

	/**
	 * Save the SimpleFieldSet of overriden keys in a file.
	 */
	private void saveTranslationFile() {
		FileOutputStream fos = null;
		File finalFile = new File(this.getL10nOverrideFileName(this.lang));

		try {
			// We don't set deleteOnExit on it : if the save operation fails, we want a backup
			// FIXME: REDFLAG: not symlink-race proof!
			File tempFile = new File(finalFile.getParentFile(), finalFile.getName() + ".bak");
			Logger.minor(this.getClass(), "The temporary filename is : " + tempFile);

			fos = new FileOutputStream(tempFile);
			this.translationOverride.writeTo(fos);

			FileUtil.renameTo(tempFile, finalFile);
			Logger.normal(this.getClass(), "Override file saved successfully!");
		} catch (IOException e) {
			Logger.error(this.getClass(), "Error while saving the translation override: " + e.getMessage(), e);
		} finally {
			Closer.close(fos);
		}
	}

	/**
	 * Get a copy of the currently used SimpleFieldSet.
	 * @return SimpleFieldSet
	 */
	public SimpleFieldSet getCurrentLanguageTranslation() {
		return (this.currentTranslation == null ? null : new SimpleFieldSet(currentTranslation));
	}

	/**
	 * Get a copy of the currently used SimpleFieldSet (overriden messages).
	 * @return SimpleFieldSet
	 */
	public SimpleFieldSet getOverrideForCurrentLanguageTranslation() {
		return (this.translationOverride == null ? null : new SimpleFieldSet(translationOverride));
	}

	/**
	 * Get the SimpleFieldSet of the default language (should be english).
	 * @return SimpleFieldSet
	 */
	public SimpleFieldSet getDefaultLanguageTranslation() {
		this.loadFallback();

		return new SimpleFieldSet(this.fallbackTranslation);

	}

	/**
	 * Get a localized string. Return "" (empty string) if it doesn't exist.
	 * @param key Key to search for.
	 * @return String
	 */
	public String getString(String key) {
		return getString(key, false);
	}

	/**
	 * Get a localized string.
	 * @param key Key to search for.
	 * @param returnNullIfNotFound If this is true, will return null if the key is not found.
	 * @return String
	 */
	public String getString(String key, boolean returnNullIfNotFound) {
		String result = null;
		if (this.translationOverride != null) {
			result = this.translationOverride.get(key);
		}

		if (result != null) {
			return result;
		}

		if (this.currentTranslation != null) {
			result = this.currentTranslation.get(key);
		}

		if (result != null) {
			return result;
		} else {
			Logger.normal(this.getClass(), "The translation for " + key + " hasn't been found (" + this.getSelectedLanguage() + ")! please tell the maintainer.");
			return (returnNullIfNotFound ? null : this.getDefaultString(key));
		}
	}

	/**
	 * Get a localized string and put it in a HTMLNode for the translation page.
	 * @param key Key to search for.
	 * @return HTMLNode
	 */
	public HTMLNode getHTMLNode(String key) {
		String value = this.getString(key, true);
		if (value != null) {
			return new HTMLNode("#", value);
		}
		HTMLNode translationField = new HTMLNode("span", "class", "translate_it");
		translationField.addChild("#", getDefaultString(key));
		translationField.addChild("a", "href", TranslationToadlet.TOADLET_URL + "?translate=" + key).addChild("small", " (translate it in your native language!)");

		return translationField;
	}

	/**
	 * Get the default value for a key.
	 * @param key Key to search for.
	 * @return String
	 */
	public String getDefaultString(String key) {
		String result = null;
		this.loadFallback();

		result = this.fallbackTranslation.get(key);


		if (result != null) {
			return result;
		}
		Logger.error(this.getClass(), "The default translation for " + key + " hasn't been found!");
		System.err.println("The default translation for " + key + " hasn't been found!");
		new Exception().printStackTrace();
		return key;
	}

	/**
	 * Get a localized string, and replace on-the-fly some values.
	 * @param key Key to search for.
	 * @param patterns Patterns to replace, ${ and } are not included.
	 * @param values Replacement values.
	 * @return String
	 */
	public String getString(String key, String[] patterns, String[] values) {
		assert (patterns.length == values.length);
		String result = getString(key);

		for (int i = 0; i < patterns.length; i++) {
			result = result.replaceAll("\\$\\{" + patterns[i] + "\\}", quoteReplacement(values[i]));
		}

		return result;
	}

	/**
	 * Get a localized string, and replace on-the-fly a value.
	 * @param key Key to search for.
	 * @param pattern Pattern to replace, ${ and } not included.
	 * @param value Replacement value.
	 * @return String
	 */
	public String getString(String key, String pattern, String value) {
		return getString(key, new String[]{pattern}, new String[]{value}); // FIXME code efficiently!
	}

	/**
	 * Escape null, $ and \.
	 * @param s String to parse
	 * @return String
	 */
	private String quoteReplacement(String s) {
		if (s == null) {
			return "(null)";
		}
		if ((s.indexOf('\\') == -1) && (s.indexOf('$') == -1)) {
			return s;
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
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
	 * Parse a localized string and put the result in a HTMLNode.
	 * @param node The result will be put in this HTMLNode.
	 * @param key Key to search for.
	 * @param patterns Patterns to replace, ${ and } are not included.
	 * @param values Replacement values.
	 */
	public void addL10nSubstitution(HTMLNode node, String key, String[] patterns, String[] values) {
		String result = HTMLEncoder.encode(getString(key));
		assert (patterns.length == values.length);
		for (int i = 0; i < patterns.length; i++) {
			result = result.replaceAll("\\$\\{" + patterns[i] + "\\}", quoteReplacement(values[i]));
		}
		node.addChild("%", result);
	}
	
	public String[] getAllNamesWithPrefix(String prefix){
		if(fallbackTranslation==null){
			return new String[]{};
		}
		List<String> toReturn=new ArrayList<String>();
		Iterator<String> it= fallbackTranslation.keyIterator();
		while(it.hasNext()){
			String key=it.next();
			if(key.startsWith(prefix)){
				toReturn.add(key);
			}
		}
		return toReturn.toArray(new String[toReturn.size()]);
	}
}
