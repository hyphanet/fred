package freenet.support;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.ServiceLoader;
import java.util.function.Consumer;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Test;

import static java.io.File.createTempFile;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.newOutputStream;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Unit test for {@link JarClassLoader}.
 */
public class JarClassLoaderTest {

	@Test
	public void serviceLoaderCanLocateTestImplementation() throws Exception {
		JarClassLoader classLoader = new JarClassLoader(createJarFileWithServiceLoaderEntry());
		ServiceLoader<TestInterface> testInterface = ServiceLoader.load(TestInterface.class, classLoader);
		List<TestInterface> implementations = new ArrayList<>();
		testInterface.iterator().forEachRemaining(implementations::add);
		assertThat(implementations, containsInAnyOrder(instanceOf(TestImplementation.class)));
	}

	@Test
	public void classLoaderCanReturnSingleUrlForDirectories() throws Exception {
		try (JarClassLoader classLoader = new JarClassLoader(createJarFileWithDirectoryEntries())) {
			URL url = classLoader.getResource("META-INF/freenet-jar-class-loader-test");
			assertThat(url, notNullValue());
		}
	}

	@Test
	public void classLoaderCanReturnUrlsForDirectories() throws Exception {
		try (JarClassLoader classLoader = new JarClassLoader(createJarFileWithDirectoryEntries())) {
			Enumeration<URL> urls = classLoader.getResources("META-INF/freenet-jar-class-loader-test");
			assertThat(urls.nextElement().toString(), containsString("jar-class-loader-test-"));
		}
	}

	/**
	 * Creates a temporary JAR file that contains a file that will be used by {@link ServiceLoader} in order to provide
	 * an implementation of {@link TestInterface}.
	 *
	 * @return A temporary JAR file containing a service loader entry
	 * @throws Exception if an error occurs
	 */
	private File createJarFileWithServiceLoaderEntry() throws Exception {
		return createJarFile(jarFileStream -> createServiceLoaderEntryFor(TestImplementation.class, jarFileStream));
	}

	/**
	 * Creates a service loader entry that will provide an instance of the given class.
	 *
	 * @param implementationClass The class of implementation to provide
	 * @param zipOutputStream The ZIP output stream
	 * @throws IOException if an I/O error occurs
	 */
	private static void createServiceLoaderEntryFor(Class<? extends TestInterface> implementationClass, ZipOutputStream zipOutputStream) throws IOException {
		ZipEntry serviceFileEntry = new ZipEntry("META-INF/services/" + TestInterface.class.getName());
		zipOutputStream.putNextEntry(serviceFileEntry);
		zipOutputStream.write((implementationClass.getName() + "\n").getBytes(UTF_8));
	}

	private File createJarFileWithDirectoryEntries() throws IOException {
		return createJarFile(jarFileStream -> {
			jarFileStream.putNextEntry(new ZipEntry("META-INF/"));
			jarFileStream.putNextEntry(new ZipEntry("META-INF/freenet-jar-class-loader-test/"));
		});
	}

	private File createJarFile(ThrowingConsumer<ZipOutputStream, IOException> outputStreamConsumer) throws IOException {
		File temporaryFile = createTempFile("jar-class-loader-test-", ".jar");
		temporaryFile.deleteOnExit();
		try (OutputStream fileOutputStream = newOutputStream(temporaryFile.toPath());
			 JarOutputStream jarFileStream = new JarOutputStream(fileOutputStream)) {
			outputStreamConsumer.accept(jarFileStream);
		}
		return temporaryFile;
	}

	/**
	 * {@link Consumer}-like interface that declares exceptions on the
	 * {@link #accept(Object)}, allowing lambdas that throw exceptions.
	 *
	 * @param <T> The type of object being consumed
	 * @param <E> The type of the exception
	 */
	private interface ThrowingConsumer<T, E extends Throwable> {
		void accept(T t) throws E;
	}

	/**
	 * Interface for use with the {@link ServiceLoader}.
	 */
	public interface TestInterface {
	}

	/**
	 * Implementation of the {@link TestInterface} interface.
	 */
	public static class TestImplementation implements TestInterface {
	}

}
