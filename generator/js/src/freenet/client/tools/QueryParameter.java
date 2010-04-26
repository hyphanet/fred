package freenet.client.tools;

/** This class represents a query parameter that has a name and value */
public class QueryParameter {
	/** The name of the parameter. Must be URL-safe. */
	private String	name;
	/** The value of the parameter. Must be URL-safe. */
	private String	value;

	/** Create a query parameter.
	 * @param name The name of the parameter. Must be URL-safe already.
	 * @param value The value of the parameter. Must be URL-safe already.
	 */
	public QueryParameter(String name, String value) {
		this.name = name;
		this.value = value;
		assert(name.indexOf('&') == -1);
		assert(value.indexOf('&') == -1);
	}

	public String getName() {
		return name;
	}

	public String getValue() {
		return value;
	}

	@Override
	public String toString() {
		return "QueryParameter[name=" + name + ",value=" + value + "]";
	}
}
