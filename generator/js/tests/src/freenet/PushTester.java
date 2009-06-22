package freenet;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.w3c.dom.NodeList;

import com.gargoylesoftware.htmlunit.TopLevelWindow;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.WebWindow;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

public class PushTester {

	public static final String	TEST_URL	= "http://127.0.0.1:8888/pushtester";

	public static final boolean	stress		= false;

	private List<Method> getAllTestMethods() {
		List<Method> methods = new ArrayList<Method>();
		for (Method m : getClass().getMethods()) {
			if (m.getName().startsWith("test") && m.getParameterTypes().length == 0) {
				methods.add(m);
			}
		}
		return methods;
	}

	private Method getMethodWithOnlyAnnotation() {
		for (Method m : getAllTestMethods()) {
			if (m.isAnnotationPresent(Only.class)) {
				return m;
			}
		}
		return null;
	}

	private List<Method> getAllIntegrationTests() {
		List<Method> methods = new ArrayList<Method>();
		for (Method m : getAllTestMethods()) {
			if (m.isAnnotationPresent(TestType.class) && m.getAnnotation(TestType.class).value() == Type.INTEGRATION) {
				methods.add(m);
			}
		}
		return methods;
	}

	private List<Method> getAllAcceptanceTests() {
		List<Method> methods = new ArrayList<Method>();
		for (Method m : getAllTestMethods()) {
			if (m.isAnnotationPresent(TestType.class) && m.getAnnotation(TestType.class).value() == Type.ACCEPTANCE) {
				methods.add(m);
			}
		}
		return methods;
	}

	private void runTests(List<Method> tests) throws Exception {
		for (Method m : tests) {
			String testName = String.valueOf(m.getName().charAt(4)).toLowerCase().concat(m.getName().substring(5));
			System.out.println("Testing: " + testName + (m.isAnnotationPresent(SecondsLong.class) ? ". Lasts:" + m.getAnnotation(SecondsLong.class).value() + " seconds" : ""));
			PrintStream err = System.err;
			System.setErr(new PrintStream(new OutputStream() {

				@Override
				public void write(int b) throws IOException {
				}
			}));
			try {
				try {
					m.invoke(this);
				} finally {
					System.setErr(err);
				}
			} catch (Exception e) {
				System.out.println("FAILED! msg:" + e.getCause().getMessage());
				e.printStackTrace();
				throw new Exception("Test failed");
			}
			System.out.println("Passed");
		}
	}

	public static void main(String[] args) {
		if (stress) {
			for (int i = 0; i < 4; i++) {
				new Thread() {
					public void run() {
						new PushTester().startTesting(true);
					};
				}.start();

			}
		} else {
			new PushTester().startTesting(false);
		}
	}

	private void startTesting(boolean stress) {
		Method only = getMethodWithOnlyAnnotation();
		if (only != null) {
			try {
				only.invoke(this);
			} catch (Exception e) {
				e.printStackTrace();
			}
			return;
		}
		if (stress) {
			List<Method> methods = getAllTestMethods();
			Collections.shuffle(methods);
			try {
				runTests(methods);
			} catch (Exception e) {
				e.printStackTrace();
			}
		} else {
			System.out.println("Integrational tests:");
			try {
				runTests(getAllIntegrationTests());
			} catch (Exception e) {
				e.printStackTrace();
				return;
			}
			System.out.println("Acceptance tests:");
			try {
				runTests(getAllAcceptanceTests());
			} catch (Exception e) {
				e.printStackTrace();
				return;
			}
		}

	}

	@SecondsLong(4)
	@TestType(Type.ACCEPTANCE)
	public void testPushing() throws Exception {
		WebClient c = new WebClient();
		WebWindow w = new TopLevelWindow("1", c);
		c.setCurrentWindow(w);
		HtmlPage p = c.getPage(TEST_URL);
		Thread.sleep(4000);
		int current = Integer.parseInt(p.getElementById("content").getFirstChild().getFirstChild().getTextContent().trim());
		if (current < 3 || current > 5) {
			throw new Exception("The value is not in the expected interval:[3,5]. The current value:" + current);
		}
	}

	@SecondsLong(5)
	@TestType(Type.ACCEPTANCE)
	public void testConnectionSharing() throws Exception {
		WebClient c = new WebClient();
		WebWindow w1 = new TopLevelWindow("1", c);
		WebWindow w2 = new TopLevelWindow("2", c);
		c.setCurrentWindow(w1);
		c.getPage(TEST_URL);
		Thread.sleep(1000);
		c.setCurrentWindow(w2);
		c.getPage(TEST_URL);
		Thread.sleep(500);
		enableDebug(w1);
		enableDebug(w2);
		Thread.sleep(4000);
		int current = Integer.parseInt(((HtmlPage) w1.getEnclosedPage()).getElementById("content").getFirstChild().getFirstChild().getTextContent().trim());
		if (current < 3 || current > 5) {
			throw new Exception("The value is not in the expected interval:[3,5] for Window 1. The current value:" + current);
		}
		current = Integer.parseInt(((HtmlPage) w2.getEnclosedPage()).getElementById("content").getFirstChild().getFirstChild().getTextContent().trim());
		if (current < 3 || current > 5) {
			throw new Exception("The value is not in the expected interval:[3,5] for Window 2. The current value:" + current);
		}
		if (getLogForWindows(w2).contains("pushnotifications") || getLogForWindows(w1).contains("pushnotifications") == false) {
			throw new Exception("Window 2 is making permanent requests or Window 1 don't");
		}
	}

	@SecondsLong(15)
	@TestType(Type.ACCEPTANCE)
	public void testFailover() throws Exception {
		WebClient c = new WebClient();
		WebWindow w1 = new TopLevelWindow("1", c);
		WebWindow w2 = new TopLevelWindow("2", c);
		c.setCurrentWindow(w1);
		c.getPage(TEST_URL);
		Thread.sleep(1000);
		c.setCurrentWindow(w2);
		c.getPage(TEST_URL);
		Thread.sleep(4000);
		c.deregisterWebWindow(w1);
		Thread.sleep(10000);
		int current = Integer.parseInt(((HtmlPage) w2.getEnclosedPage()).getElementById("content").getFirstChild().getFirstChild().getTextContent().trim());
		if (current < 13 || current > 15) {
			throw new Exception("The value is not in the expected interval:[13,15] for Window 2. The current value:" + current);
		}
	}

	public static String getLogForWindows(WebWindow... windows) {
		List<String> log = new ArrayList<String>();
		for (WebWindow w : windows) {
			NodeList logElements = ((HtmlPage) w.getEnclosedPage()).getElementById("log").getElementsByTagName("div");
			for (int i = 0; i < logElements.getLength(); i++) {
				String msg = logElements.item(i).getTextContent() + "\n";
				msg = msg.substring(0, msg.indexOf("}") + 1).concat("{" + w.getName() + "}").concat(msg.substring(msg.indexOf("}") + 1));
				log.add(msg);
			}
		}
		Collections.sort(log);
		StringBuilder sb = new StringBuilder();
		for (String s : log) {
			sb.append(s);
		}
		return sb.toString();
	}

	public static void logToWindow(WebWindow window, String msg) {
		((HtmlPage) window.getEnclosedPage()).executeJavaScript("window.log(\"" + msg + "\");");
	}
	
	public static void enableDebug(WebWindow window){
		((HtmlPage) window.getEnclosedPage()).executeJavaScript("window.enableDebug();");
	}
}
