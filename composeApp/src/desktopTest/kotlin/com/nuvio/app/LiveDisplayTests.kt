package com.nuvio.app

import java.awt.GraphicsEnvironment
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue

/** Separate permission from native-library availability and focus-test selection. */
internal object LiveDisplayTests {
    const val OPT_IN_ENV = "NUVIO_RUN_LIVE_DISPLAY_TESTS"

    fun assumeEnabled(
        optIn: String? = System.getenv(OPT_IN_ENV),
        isHeadless: () -> Boolean = { GraphicsEnvironment.isHeadless() },
    ) {
        // Check permission before even initializing the AWT graphics environment.
        assumeTrue(
            "Live display test skipped: requires explicit $OPT_IN_ENV=1; may disrupt the desktop session",
            optIn == "1",
        )
        assumeFalse("Live display test skipped: a non-headless display is required", isHeadless())
    }
}
