/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.l10n;

import java.io.File;

import freenet.l10n.BaseL10n.LANGUAGE;

/**
 * This is the interface used to localize the Node. It just wraps a BaseL10n
 * with correct parameters so that this class can entierly be used using
 * static methods, which is much more practical to use than non-static methods.
 *
 * Why doesn't that class extends BaseL10n ? Because it has to be static.
 *
 * How to change the language ? Simply create a new NodeL1On object. You don't have
 * to stock the created object because you can access it using static methods.
 * @author Artefact2
 */
public class NodeL10n {

	public static final String L10N_FILES_MASK = "freenet.l10n.${lang}.properties";

	public static final String L10N_OVERRIDE_FILES_MASK
		= "freenet.l10n.${lang}.override.properties";

	private static BaseL10n b;

	/**
	 * Initialize the Node localization with the node's default language, and
	 * overrides in the working directory.
	 */
	public NodeL10n() {
		this(LANGUAGE.getDefault(), new File("."));
	}

	/**
	 * Initialize the Node localization. You must also call that constructor
	 * if you want to change the language.
	 * @param lang Language to use.
	 * @see LANGUAGE#mapToLanguage(String)
	 */
	public NodeL10n(final LANGUAGE lang, File overrideDir) {
		NodeL10n.b = new BaseL10n("freenet/l10n/", L10N_FILES_MASK,
		  overrideDir.getPath()+File.separator+L10N_OVERRIDE_FILES_MASK, lang);
	}

	/**
	 * Typically for unit test purposes only:
	 * Constructor which supports overriding the default directory of the l10n files. */
	public NodeL10n(final LANGUAGE lang, File l10nDir, File overrideDir) {
		NodeL10n.b = new BaseL10n(l10nDir.toString(), L10N_FILES_MASK,
			overrideDir.getPath()+File.separator+L10N_OVERRIDE_FILES_MASK, lang);
	}

	/**
	 * Get the BaseL10n used to localize the node.
	 * @return BaseL10n.
	 * @see BaseL10n
	 */
	public static BaseL10n getBase() {
		if(b==null){
			new NodeL10n();
		}
		return b;
	}
}
