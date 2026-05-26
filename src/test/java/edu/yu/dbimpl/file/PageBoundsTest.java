package edu.yu.dbimpl.file;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test class is specifically designed to validate the bounds-checking
 * logic of the Page class. It ensures that any attempt to read or write data
 * that would cross the page's capacity boundary correctly throws an
 * IllegalArgumentException, preventing the underlying buffer from throwing
 * a more generic IndexOutOfBoundsException.
 */
class PageBoundsTest {

    private final int blockSize = 100;
    private Page page;

    @BeforeEach
    void setUp() {
        page = new Page(blockSize);
    }

    @Nested
    @DisplayName("Integer Bounds Tests")
    class IntegerTests {
        private final int intSize = Integer.BYTES; // 4 bytes

        @Test
        @DisplayName("Set/Get Int at the very last possible position (should succeed)")
        void testIntAtExactBoundary() {
            int offset = blockSize - intSize; // 100 - 4 = 96
            int value = 12345;
            assertDoesNotThrow(() -> page.setInt(offset, value));
            assertEquals(value, page.getInt(offset));
        }

        @Test
        @DisplayName("Set Int where start is in-bounds but end is out-of-bounds")
        void testSetIntCrossingBoundary() {
            int offset = blockSize - intSize + 1; // 97
            assertThrows(IllegalArgumentException.class, () -> page.setInt(offset, 99));
        }

        @Test
        @DisplayName("Get Int where start is in-bounds but end is out-of-bounds")
        void testGetIntCrossingBoundary() {
            int offset = blockSize - intSize + 1; // 97
            assertThrows(IllegalArgumentException.class, () -> page.getInt(offset));
        }

        @Test
        @DisplayName("Access Int with negative offset")
        void testIntAtNegativeOffset() {
            assertThrows(IllegalArgumentException.class, () -> page.setInt(-1, 10));
            assertThrows(IllegalArgumentException.class, () -> page.getInt(-1));
        }
    }

    @Nested
    @DisplayName("Bytes/String Bounds Tests (The Core Fix)")
    class BytesAndStringTests {
        private final int intSize = Integer.BYTES; // 4 bytes for the length prefix

        @Test
        @DisplayName("Get Bytes where offset for LENGTH is out-of-bounds")
        void testGetBytesWhereLengthIsOutOfBounds() {
            // Trying to read the 4-byte length at offset 98 will fail in a 100-byte page
            int offset = blockSize - intSize + 2; // 98
            assertThrows(IllegalArgumentException.class, () -> page.getBytes(offset));
        }

        @Test
        @DisplayName("Get String where offset for LENGTH is out-of-bounds")
        void testGetStringWhereLengthIsOutOfBounds() {
            int offset = blockSize - intSize + 2; // 98
            assertThrows(IllegalArgumentException.class, () -> page.getString(offset));
        }

        @Test
        @DisplayName("Set Bytes where data would write past the boundary")
        void testSetBytesCrossingBoundary() {
            byte[] data = new byte[10];
            // Try to write 4 bytes (length) + 10 bytes (data) starting at offset 90
            int offset = blockSize - intSize - 9; // 90
            assertThrows(IllegalArgumentException.class, () -> page.setBytes(offset, data));
        }

        @Test
        @DisplayName("Set String where data would write past the boundary")
        void testSetStringCrossingBoundary() {
            String data = "This string is too long";
            // Place it near the end so its bytes + length prefix will overflow
            int offset = 80;
            assertThrows(IllegalArgumentException.class, () -> page.setString(offset, data));
        }

        @Test
        @DisplayName("Get Bytes where stored length points out-of-bounds")
        void testGetBytesWithMaliciousLength() {
            int offset = 50;
            int maliciousLength = 60; // 50 + 4 (for length) + 60 > 100

            // Manually write a bad length into the page
            page.setInt(offset, maliciousLength);

            // Now, try to read the byte array. The method should detect the invalid length.
            assertThrows(IllegalArgumentException.class, () -> page.getBytes(offset));
        }
    }

    @Nested
    @DisplayName("Double Bounds Tests")
    class DoubleTests {
        private final int doubleSize = Double.BYTES; // 8 bytes

        @Test
        @DisplayName("Set/Get Double at the very last possible position (should succeed)")
        void testDoubleAtExactBoundary() {
            int offset = blockSize - doubleSize; // 100 - 8 = 92
            double value = 123.456;
            assertDoesNotThrow(() -> page.setDouble(offset, value));
            assertEquals(value, page.getDouble(offset));
        }

        @Test
        @DisplayName("Set Double crossing the boundary")
        void testSetDoubleCrossingBoundary() {
            int offset = blockSize - doubleSize + 1; // 93
            assertThrows(IllegalArgumentException.class, () -> page.setDouble(offset, 99.9));
        }
    }
}
