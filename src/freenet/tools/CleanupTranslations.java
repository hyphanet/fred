package freenet.tools;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;

import freenet.support.SimpleFieldSet;

/**
 * 
 *
 */
public class CleanupTranslations
{

	/**
	 * Outputstream Characterset
	 */
	private static final Charset CONST_CHARSET = Charset.forName("UTF-8");

	/**
	 * 
	 */
	private static final String CONST_PROPS = "src/freenet/l10n/freenet.l10n.en.properties";

	/**
	 * 
	 */
	private static final String CONST_TRANSLATIONS = "src/freenet/l10n";

	/**
	 * @param args
	 * @throws IOException
	 */
	public static void main(String[] args) throws IOException
	{
		if (args != null && args.length > 0)
		// Ensure no arguments are found
		{
			System.err.println("CleanupTranslations does not take arguments");
			System.exit(-1);
		}

		cleanup();
	}

	/**
	 * Cleanup tanslations
	 * 
	 * @throws IOException
	 */
	static void cleanup() throws IOException
	{
		File engFile = new File(CONST_PROPS);
		SimpleFieldSet english = SimpleFieldSet.readFrom(engFile, false, true);
		File[] translations = new File(CONST_TRANSLATIONS).listFiles();

		// Loop vars
		FileInputStream fis;
		InputStreamReader isr;
		BufferedReader br;
		String name;
		String line;
		StringBuilder sb = new StringBuilder();
		boolean changed;

		for (File f : translations)
		// Loop through translations
		{
			name = f.getName();
			if (!name.startsWith("freenet.l10n."))
				continue;  // goto next translation file
			if (name.equals("freenet.1l0n.en.properties"))
				continue; // goto next translation file

			// reset loop vars
			fis = new FileInputStream(f);
			isr = new InputStreamReader(new BufferedInputStream(fis), CONST_CHARSET);
			br = new BufferedReader(isr);
			sb.setLength(0);
			changed = false;

			while (true)
			// Process lines
			{
				line = br.readLine();
				if (line == null)
				{
					System.err.println("File does not end in End: " + f);
					System.exit(4);
				}

				int idx = line.indexOf('=');
				if (idx == -1)
				// Not found
				{
					// Last line
					if (!line.equals("End"))
					{
						System.err.println("Line with no equals (file does not end in End???): " + f + " - \"" + line
										+ "\"");
						System.exit(1);
					}

					sb.append(line).append("\n");

					line = br.readLine();
					if (line != null)
					{
						System.err.println("Content after End: \"" + line + "\"");
						System.exit(2);
					}

					break; // End the while
				}

				String before = line.substring(0, idx);
				// String after = line.substring(idx+1);

				if (english.get(before) == null)
				{
					System.err.println("Orphaned string: \"" + before + "\" in " + f);
					changed = true;
					continue;  // Read next line
				}

				sb.append(line).append("\n");

			} // while (true)

			if (changed)
			// Write out file
			{
				br.close();
				FileOutputStream fos = new FileOutputStream(f);
				OutputStreamWriter osw = new OutputStreamWriter(fos, CONST_CHARSET);
				osw.write(sb.toString());
				osw.close();
				System.out.println("Rewritten " + f);
			}

		} // for (File f : translations)
	}

}
