package top.tianyan.app.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelIntegerInputTest {
    @Test fun `blank retains the default`() {
        assertTrue(isValidOptionalModelInteger("", 1))
        assertTrue(isValidOptionalModelInteger("  ", 1))
    }

    @Test fun `zero is allowed only for unlimited RPM`() {
        assertTrue(isValidOptionalModelInteger("0", 0))
        assertFalse(isValidOptionalModelInteger("0", 1))
        assertTrue(isValidOptionalModelInteger(" 20000 ", 1))
    }

    @Test fun `invalid and overflowing values are rejected`() {
        for (input in listOf("-1", "1.5", "abc", "2147483648", "999999999999999999")) {
            assertFalse(input, isValidOptionalModelInteger(input, 0))
        }
        assertTrue(isValidOptionalModelInteger("2147483647", 1))
    }
}
