package edu.yu.dbimpl.file;

import org.junit.Test;
import org.junit.Before;
import static org.junit.Assert.*;

public class PageTest {
    private Page page;
    private final int blocksize = 1024;

    @Before
    public void setUp() {
        page = new Page(blocksize);
    }

    @Test
    public void testSetAndGetInt() {
        int offset = 100;
        int value = 42;
        page.setInt(offset, value);
        assertEquals(value, page.getInt(offset));

        // Test maximum integer
        page.setInt(0, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, page.getInt(0));

        // Test minimum integer
        page.setInt(8, Integer.MIN_VALUE);
        assertEquals(Integer.MIN_VALUE, page.getInt(8));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetIntInvalidOffsetNegative() {
        page.setInt(-1, 42);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetIntInvalidOffsetTooLarge() {
        page.setInt(blocksize - 2, 42);
    }

    @Test
    public void testSetAndGetDouble() {
        int offset = 200;
        double value = 3.14159;
        page.setDouble(offset, value);
        assertEquals(value, page.getDouble(offset), 0.0001);

        // Test very large and small doubles
        page.setDouble(0, Double.MAX_VALUE);
        assertEquals(Double.MAX_VALUE, page.getDouble(0), 0.0001);

        page.setDouble(16, Double.MIN_VALUE);
        assertEquals(Double.MIN_VALUE, page.getDouble(16), 0.0001);
    }

    @Test
    public void testSetAndGetBoolean() {
        int offset = 300;
        page.setBoolean(offset, true);
        assertTrue(page.getBoolean(offset));

        page.setBoolean(offset, false);
        assertFalse(page.getBoolean(offset));
    }

    @Test
    public void testSetAndGetBytes() {
        int offset = 400;
        byte[] data = "Hello, World!".getBytes();
        page.setBytes(offset, data);
        assertArrayEquals(data, page.getBytes(offset));

        // Test empty byte array
        byte[] emptyData = new byte[0];
        page.setBytes(0, emptyData);
        assertArrayEquals(emptyData, page.getBytes(0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetBytesTooLarge() {
        byte[] hugeArray = new byte[blocksize];
        page.setBytes(0, hugeArray);
    }

    @Test
    public void testSetAndGetString() {
        int offset = 500;
        String text = "Hello, World!";
        page.setString(offset, text);
        assertEquals(text, page.getString(offset));

        // Test empty string
        page.setString(0, "");
        assertEquals("", page.getString(0));

        // Test string with special characters
        String special = "Special chars: !@#$%^&*()_+";
        page.setString(100, special);
        assertEquals(special, page.getString(100));
    }

    @Test
    public void testMultipleWrites() {
        // Test writing different types to different locations
        page.setInt(0, 42);
        page.setDouble(8, 3.14);
        page.setBoolean(16, true);
        page.setString(20, "test");

        // Verify all values are preserved
        assertEquals(42, page.getInt(0));
        assertEquals(3.14, page.getDouble(8), 0.0001);
        assertTrue(page.getBoolean(16));
        assertEquals("test", page.getString(20));
    }
}
