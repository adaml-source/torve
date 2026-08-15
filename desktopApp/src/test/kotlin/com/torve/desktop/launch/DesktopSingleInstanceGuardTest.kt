package com.torve.desktop.launch

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DesktopSingleInstanceGuardTest {
    @Test
    fun secondProcessCannotAcquireLockUntilFirstReleasesIt() {
        val directory = createTempDirectory("torve-instance-guard").toFile()
        val lockFile = directory.resolve("torve.instance.lock")
        val first = assertNotNull(DesktopSingleInstanceHandle.tryAcquire(lockFile))
        try {
            assertNull(DesktopSingleInstanceHandle.tryAcquire(lockFile))
        } finally {
            first.close()
        }

        assertNotNull(DesktopSingleInstanceHandle.tryAcquire(lockFile)).close()
        lockFile.delete()
        directory.delete()
    }
}
