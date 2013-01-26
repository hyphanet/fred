package freenet.node.simulator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import freenet.support.Fields;

/** Base class for long-term tests that use a CSV file to store status */
public class LongTermTest {
	
	protected static final DateFormat dateFormat = new SimpleDateFormat("yyyy.MM.dd", Locale.US);
	static {
		dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
	}
	protected static final Calendar today = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
	
	protected static final int EXIT_NO_SEEDNODES = 257;
	protected static final int EXIT_FAILED_TARGET = 258;
	protected static final int EXIT_THREW_SOMETHING = 261;
	
	protected static final String ENCODING = "UTF-8";

	protected static void writeToStatusLog(File file, List<String> csvLine) {
		try {
			FileOutputStream fos = new FileOutputStream(file, true);
			OutputStreamWriter w = new OutputStreamWriter(fos);
			w.write(Fields.commaList(csvLine.toArray(), '!')+"\n");
			w.close();
		} catch (IOException e) {
			System.err.println("Exiting due to IOException "+e+" writing status file");
			e.printStackTrace();
			System.exit(EXIT_THREW_SOMETHING);
		}
	}

}
