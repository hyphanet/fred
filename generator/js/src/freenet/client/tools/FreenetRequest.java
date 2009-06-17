package freenet.client.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;

import freenet.client.FreenetJs;

public class FreenetRequest {

	public static Request sendRequest(String path, QueryParameter[] parameters) {
		return sendRequest(path, parameters, null);
	}

	public static Request sendRequest(String path, QueryParameter parameter) {
		return sendRequest(path, new QueryParameter[] { parameter });
	}

	public static Request sendRequest(String path, QueryParameter parameter, RequestCallback callback) {
		return sendRequest(path, new QueryParameter[] { parameter }, callback);
	}

	public static Request sendRequest(String path, QueryParameter[] parameters, RequestCallback callback) {
		if (parameters == null) {
			parameters = new QueryParameter[1];
		}
		List<QueryParameter> paramList = new ArrayList<QueryParameter>(Arrays.asList(parameters));
		paramList.add(new QueryParameter("timestamp", "" + System.currentTimeMillis()));
		String queryString = new String();
		queryString = queryString.concat("?");
		for (int i = 0; i < paramList.size(); i++) {
			queryString = queryString.concat(paramList.get(i).getName() + "=" + paramList.get(i).getValue());
			if (i != paramList.size() - 1) {
				queryString = queryString.concat("&");
			}
		}
		if (callback == null) {
			callback = new RequestCallback() {

				@Override
				public void onResponseReceived(Request request, Response response) {
				}

				@Override
				public void onError(Request request, Throwable exception) {
				}
			};
		}
		try {
			return new RequestBuilder(RequestBuilder.GET, path + queryString).sendRequest(null, callback);
		} catch (RequestException e) {
			callback.onError(null, e);
			return null;
		}
	}
}
