package freenet.config;

import java.util.ArrayList;
import java.util.List;

import freenet.support.Logger;
import freenet.support.LoggerHook;
import freenet.support.SimpleFieldSet;
import org.junit.Test;

import static freenet.support.Logger.LogLevel.MINIMAL;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

public class PersistentConfigTest {

	@Test
	public void configDoesNotLogAnErrorWhenIgnoredOptionIsRead() {
		List<String> messages = interceptLogger(config::finishedInit);
		assertThat(messages, empty());
	}

	@Test
	public void configDoesNotContainIgnoredOptionWhenExported() {
		assertThat(config.exportFieldSet().isEmpty(), equalTo(true));
	}

	private List<String> interceptLogger(Runnable runnable) {
		List<String> messages = new ArrayList<>();
		LoggerHook loggerHook = new LoggerHook(MINIMAL) {
			@Override
			public void log(Object o, Class<?> source, String message, Throwable e, LogLevel priority) {
				if (source == PersistentConfig.class) {
					messages.add(message);
				}
			}
		};
		Logger.globalAddHook(loggerHook);
		runnable.run();
		Logger.globalRemoveHook(loggerHook);
		return messages;
	}

	private final SimpleFieldSet fieldSetWithIgnoredOption = new SimpleFieldSet(true);

	{
		fieldSetWithIgnoredOption.put("sub.ignored", true);
	}

	private final PersistentConfig config = new PersistentConfig(fieldSetWithIgnoredOption);
	private final SubConfig subConfig = config.createSubConfig("sub");

	{
		subConfig.registerIgnoredOption("ignored");
		subConfig.finishedInitialization();
	}

}
