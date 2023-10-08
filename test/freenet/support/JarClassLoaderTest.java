package freenet.support;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
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
		JarClassLoader classLoader = new JarClassLoader(createJarFileWithTwoServiceLoaderEntries());
		ServiceLoader<TestInterface> testInterface = ServiceLoader.load(TestInterface.class, classLoader);
		List<TestInterface> implementations = new ArrayList<>();
		testInterface.iterator().forEachRemaining(implementations::add);
		assertThat(implementations, containsInAnyOrder(
				instanceOf(TestImplementation1.class),
				instanceOf(TestImplementation2.class)
		));
	}

	/**
	 * Create a temporary JAR file that contains two files that will be used by
	 * {@link ServiceLoader} in order to provide two different implementations
	 * of {@link TestInterface}.
	 *
	 * @return A temporary JAR file containing two service loader entries
	 * @throws Exception if an error occurs
	 */
	private File createJarFileWithTwoServiceLoaderEntries() throws Exception {
		File temporaryFile = createTempFile("jar-class-loader-test-", ".jar");
		temporaryFile.deleteOnExit();
		try (
				OutputStream fileOutputStream = newOutputStream(temporaryFile.toPath());
				JarOutputStream jarFileStream = new JarOutputStream(fileOutputStream)) {
			createServiceLoaderEntryFor(TestImplementation1.class, jarFileStream);
			clearNames(jarFileStream);
			createServiceLoaderEntryFor(TestImplementation2.class, jarFileStream);
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
	 * Clears the names of already written entries of the given {@link ZipOutputStream}.
	 * This is necessary to prevent exceptions because of duplicate file entries because
	 * we absolutely <em>do</em> want multiple files with the same name here.
	 *
	 * @param zipOutputStream The ZIP output stream to process
	 * @throws Exception if there’s a reflection error
	 */
	private void clearNames(ZipOutputStream zipOutputStream) throws Exception {
		Field namesField = ZipOutputStream.class.getDeclaredField("names");
		namesField.setAccessible(true);
		Set<String> names = (Set<String>) namesField.get(zipOutputStream);
		names.clear();
	}

	/**
	 * Interface for use with the {@link ServiceLoader}.
	 */
	public interface TestInterface {
	}

	/**
	 * First implementation of the {@link TestInterface} interface.
	 */
	public static class TestImplementation1 implements TestInterface {
	}

	/**
	 * Second implementation of the {@link TestInterface} interface.
	 */
	public static class TestImplementation2 implements TestInterface {
	}

}
