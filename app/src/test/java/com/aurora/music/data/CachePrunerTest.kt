package com.aurora.music.data

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CachePrunerTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = createTempDirectory("cache_pruner_test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun ttlDeletesExpiredEntriesRegardlessOfSize() {
        val now = 1_000_000_000L
        val dayMs = 24L * 60L * 60L * 1000L
        val oldFile = File(root, "old.bin").apply {
            writeBytes(ByteArray(100))
            setLastModified(now - 10L * dayMs)
        }
        val freshFile = File(root, "fresh.bin").apply {
            writeBytes(ByteArray(100))
            setLastModified(now - 2L * dayMs)
        }

        val remaining = CachePruner.pruneEntries(
            entries = listOf(
                CachePruner.Entry(oldFile, 100L, now - 10L * dayMs),
                CachePruner.Entry(freshFile, 100L, now - 2L * dayMs),
            ),
            maxBytes = 0L,
            maxAgeMs = 7L * dayMs,
            nowMs = now,
        )

        assertFalse(oldFile.exists())
        assertTrue(freshFile.exists())
        assertEquals(100L, remaining)
    }

    @Test
    fun lruEvictsOldestUntilUnderMaxBytes() {
        val now = 1_000_000_000L
        val oldest = File(root, "a.bin").apply { writeBytes(ByteArray(50)) }
        val middle = File(root, "b.bin").apply { writeBytes(ByteArray(60)) }
        val newest = File(root, "c.bin").apply { writeBytes(ByteArray(70)) }

        val remaining = CachePruner.pruneEntries(
            entries = listOf(
                CachePruner.Entry(middle, 60L, now - 2_000L),
                CachePruner.Entry(oldest, 50L, now - 3_000L),
                CachePruner.Entry(newest, 70L, now - 1_000L),
            ),
            maxBytes = 100L,
            maxAgeMs = 0L,
            nowMs = now,
        )

        assertFalse(oldest.exists())
        assertFalse(middle.exists())
        assertTrue(newest.exists())
        assertEquals(70L, remaining)
    }

    @Test
    fun pruneSubdirsEvictsWholeBookDirectoryByLru() {
        val now = 1_000_000_000L
        val oldDir = File(root, "book_old").apply {
            mkdirs()
            File(this, "parsed_v3.json").apply {
                writeBytes(ByteArray(80))
                setLastModified(now - 5_000L)
            }
        }
        val newDir = File(root, "book_new").apply {
            mkdirs()
            File(this, "parsed_v3.json").apply {
                writeBytes(ByteArray(80))
                setLastModified(now - 1_000L)
            }
        }

        CachePruner.pruneSubdirs(root, maxBytes = 100L, maxAgeMs = 0L, nowMs = now)

        assertFalse(oldDir.exists())
        assertTrue(newDir.exists())
    }
}
