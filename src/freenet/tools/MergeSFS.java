package freenet.tools;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;

import freenet.support.SimpleFieldSet;

/**
 * Merges changes made in a SFS override file to a SFS source file.
 * 
 */
public class MergeSFS
{

	/**
	 * Outputstream Characterset
	 */
	private static final Charset CONST_CHARSET = Charset.forName("UTF-8");

	/**
	 * Usage message
	 */
	private static final String CONST_MSG_USE =
					"Merges changes made in a SFS override file to a SFS source file.\n" +
									"Usage: source-file override-file [--stdout]\n" +
									"    By default the merged file is written to source-file.\n" +
									"    --stdout writes to standard output instead.";

	/**
	 * @param args
	 *            source-file override-file [--stdout]
	 * @throws IOException
	 */
	public static void main(String[] args) throws IOException
	{
		if (args.length < 2 || args.length > 3)
		{
			System.out.println(CONST_MSG_USE);
			return;
		}

		File f1 = new File(args[0]);
		File f2 = new File(args[1]);

		SimpleFieldSet fs1 = SimpleFieldSet.readFrom(f1, false, true);
		SimpleFieldSet fs2 = SimpleFieldSet.readFrom(f2, false, true);
		fs1.putAllOverwrite(fs2);
		// Force output to UTF-8. A PrintStream is still an OutputStream.
		// These files are always UTF-8, and stdout is likely to be redirected into one.
		final OutputStream os;
		if (args.length == 3 && args[2].equals("--stdout"))
		{
			os = System.out;
		}
		else
		{
			os = new FileOutputStream(f1);
		}
		Writer w = new BufferedWriter(new OutputStreamWriter(os, CONST_CHARSET));
		fs1.writeToOrdered(w);
		w.flush();
	}
}
