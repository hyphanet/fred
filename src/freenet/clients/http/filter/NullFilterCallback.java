package freenet.clients.http.filter;


public class NullFilterCallback implements FilterCallback {

	public boolean allowGetForms() {
		return false;
	}

	public boolean allowPostForms() {
		return false;
	}

	public String processURI(String uri, String overrideType) {
		return null;
	}

}
