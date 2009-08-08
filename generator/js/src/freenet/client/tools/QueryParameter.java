package freenet.client.tools;

/** This class represents a query parameter that has a name and value */
public class QueryParameter {
	/** The name of the parameter */
	private String	name;
	/** The value of the parameter */
	private String	value;

	public QueryParameter(String name, String value) {
		this.name = name;
		this.value = value;
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
