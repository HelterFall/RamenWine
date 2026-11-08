package dev.ramenwine.runtime

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeLayoutTest {
    @Test
    fun missingRuntimeIsNotInstalled() {
        val root = Files.createTempDirectory("ramenwine-runtime").toFile()
        try {
            val result = RuntimeLayout.inspect(root)
            assertEquals(RuntimeState.NOT_INSTALLED, result.state)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun incompleteRuntimeDoesNotPassReadinessCheck() {
        val root = Files.createTempDirectory("ramenwine-runtime").toFile()
        try {
            java.io.File(root, "runtime/current").mkdirs()
            val result = RuntimeLayout.inspect(root)
            assertEquals(RuntimeState.INCOMPLETE, result.state)
        } finally {
            root.deleteRecursively()
        }
    }
}
