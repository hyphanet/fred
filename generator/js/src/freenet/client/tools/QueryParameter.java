package freenet.client.tools;

public class QueryParameter {
	private String	name;
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
		return "QueryParameter[name="+name+",value="+value+"]";
	}
}
