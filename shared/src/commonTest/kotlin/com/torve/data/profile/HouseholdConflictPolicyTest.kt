package com.torve.data.profile

import kotlinx.serialization.json.*
import kotlin.test.*

class HouseholdConflictPolicyTest {
    private fun record(at: Long = 100, deleted: Boolean = false, position: Int = 10) = HouseholdRecord(
        "default", "progress", "series-episode", at, deleted, buildJsonObject { put("positionMs", position) })
    @Test fun equalTimestampEditsConvergeRegardlessOfArrivalOrder() {
        val a = record(position = 100); val b = record(position = 200)
        assertEquals(-compareHouseholdRecords(b, a), compareHouseholdRecords(a, b))
        fun merge(x: HouseholdRecord, y: HouseholdRecord) = if (compareHouseholdRecords(x, y) >= 0) x else y
        assertEquals(merge(a, b), merge(b, a))
        repeat(100) { assertEquals(merge(a, b), merge(merge(b, a), b)) }
    }
    @Test fun explicitRewindUsesTheNewerEditRatherThanTheLargestPosition() {
        assertTrue(compareHouseholdRecords(record(101, position = 5), record(100, position = 500)) > 0)
    }
    @Test fun removalWinsATieButANewerExplicitRewatchCanReplaceIt() {
        assertTrue(compareHouseholdRecords(record(deleted = true), record()) > 0)
        assertTrue(compareHouseholdRecords(record(101), record(deleted = true)) > 0)
    }
    @Test fun objectInsertionOrderIsNotAConflict() {
        val first = record().copy(data = buildJsonObject { put("a", 1); put("b", buildJsonObject { put("y", 2); put("x", 1) }) })
        val second = first.copy(data = buildJsonObject { put("b", buildJsonObject { put("x", 1); put("y", 2) }); put("a", 1) })
        assertEquals(0, compareHouseholdRecords(first, second))
    }
    @Test fun profilesCannotBeMergedAsOneRecord() {
        assertFailsWith<IllegalArgumentException> { compareHouseholdRecords(record(), record().copy(profile = "kids")) }
    }
}
