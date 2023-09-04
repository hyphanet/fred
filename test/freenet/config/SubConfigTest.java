package freenet.config;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.equalTo;

public class SubConfigTest {

	@Test
	public void registeredIgnoredOptionDoesNotShowUpInRegisteredOptions() {
		subConfig.registerIgnoredOption("ignored");
		assertThat(subConfig.getOptions(), emptyArray());
	}

	@Test
	public void ignoredOptionIsNotExported() {
		subConfig.registerIgnoredOption("ignored");
		assertThat(subConfig.exportFieldSet().isEmpty(), equalTo(true));
	}

	private final Config config = new Config();
	private final SubConfig subConfig = config.createSubConfig("");

}
