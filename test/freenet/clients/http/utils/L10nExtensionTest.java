package freenet.clients.http.utils;

import java.util.Locale;
import java.util.Map;

import com.mitchellbosecke.pebble.extension.Function;
import com.mitchellbosecke.pebble.template.EvaluationContext;
import org.junit.Test;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

public class L10nExtensionTest {

	@Test
	public void l10nExtensionExposesAnL10nFunction() {
		assertThat(l10nExtension.getFunctions().keySet(), contains("l10n"));
	}

	@Test
	public void l10nFunctionDoesNotHaveArguments() {
		assertThat(l10nFunction.getArgumentNames(), nullValue());
	}

	@Test
	public void l10nFunctionReturnStringNullIfNotArgumentsAreGiven() {
		assertThat(l10nFunction.execute(emptyMap(), null, null, 0), equalTo("null"));
	}

	@Test
	public void l10nFunctionRetrievesGivenArgumentWithPrefixFromContextToNodeL10n() {
		Map<String, Object> arguments = singletonMap("0", "l10nFunctionTest");
		EvaluationContext context = createEvaluationContext();
		assertThat(l10nFunction.execute(arguments, null, context, 0), equalTo("Localized Value"));
	}

	private static EvaluationContext createEvaluationContext() {
		return new EvaluationContext() {
			@Override
			public boolean isStrictVariables() {
				return false;
			}

			@Override
			public Locale getLocale() {
				return null;
			}

			@Override
			public Object getVariable(String key) {
				return key.equals("l10nPrefix") ? "test." : null;
			}
		};
	}

	private final L10nExtension l10nExtension = new L10nExtension();
	private final Function l10nFunction = l10nExtension.getFunctions().get("l10n");

}
