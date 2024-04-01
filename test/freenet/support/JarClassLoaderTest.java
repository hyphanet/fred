package freenet.support;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Test;

import static java.io.File.createTempFile;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.newOutputStream;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.instanceOf;

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

	/**
	 * Creates a temporary JAR file that contains a file that will be used by {@link ServiceLoader} in order to provide
	 * an implementation of {@link TestInterface}.
	 *
	 * @return A temporary JAR file containing a service loader entry
	 * @throws Exception if an error occurs
	 */
	private File createJarFileWithServiceLoaderEntry() throws Exception {
		File temporaryFile = createTempFile("jar-class-loader-test-", ".jar");
		temporaryFile.deleteOnExit();
		try (
				OutputStream fileOutputStream = newOutputStream(temporaryFile.toPath());
				JarOutputStream jarFileStream = new JarOutputStream(fileOutputStream)) {
			createServiceLoaderEntryFor(TestImplementation.class, jarFileStream);
		}
		return temporaryFile;
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
