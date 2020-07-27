package de.intranda.goobi.plugins.replace_images;

import static org.junit.Assert.assertEquals;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class HandlersTest {

    @Test(expected = IllegalArgumentException.class)
    public void testSanitizePathThrowsOnRelative() {
        Path root = Paths.get("/test/path/to/somewhere");
        Handlers.resolveSanitized(root, "../../../../etc/passwd");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSanitizePathThrowsOnRelative2() {
        Path root = Paths.get("/test/path/to/somewhere");
        Handlers.resolveSanitized(root, "../etc/passwd");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSanitizePathThrowsOnAbsolute() {
        Path root = Paths.get("/test/path/to/somewhere");
        Handlers.resolveSanitized(root, "/etc/passwd");
    }

    @Test
    public void testSanitizePathWorks() {
        Path root = Paths.get("/test/path/to/somewhere");
        Path p = Handlers.resolveSanitized(root, "00000001.tif");
        assertEquals("Path should be resolved", "/test/path/to/somewhere/00000001.tif", p.toString());
    }
}
