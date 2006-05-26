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

	public String onBaseHref(String baseHref) {
		return null;
	}

	public void onText(String s) {
		// Do nothing
	}

}
