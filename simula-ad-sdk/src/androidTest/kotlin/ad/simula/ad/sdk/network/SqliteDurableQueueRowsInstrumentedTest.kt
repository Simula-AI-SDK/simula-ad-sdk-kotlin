package ad.simula.ad.sdk.network

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqliteDurableQueueRowsInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun sqliteRowsPersistOrderingConflictAndRevisionSemantics() {
        val databaseName = "durable-rows-${UUID.randomUUID()}.db"
        try {
            val rows = SqliteDurableQueueRows(context, databaseName, "pending_actions")
            val first = DurableQueueRow("first", "revision-1", 20L, "first-payload")
            val second = DurableQueueRow("second", "revision-1", 10L, "second-payload")

            assertEquals(DurableMutationResult.Applied, rows.upsertAll(listOf(first, second)))
            assertEquals(listOf(second, first), loaded(rows.load()))

            val ignored = first.copy(rowId = "ignored", payload = "ignored-payload")
            assertEquals(DurableMutationResult.NoMatch, rows.insertIfAbsent(ignored))
            assertEquals(first, loaded(rows.load()).last())

            val replacement = first.copy(rowId = "revision-2", payload = "replacement-payload")
            assertEquals(
                DurableMutationResult.NoMatch,
                rows.replaceIfRevision(first.key, "wrong-revision", replacement),
            )
            assertEquals(
                DurableMutationResult.Applied,
                rows.replaceIfRevision(first.key, first.rowId, replacement),
            )
            assertEquals(
                DurableMutationResult.NoMatch,
                rows.removeIfRevision(first.key, first.rowId),
            )

            val reopened = SqliteDurableQueueRows(context, databaseName, "pending_actions")
            assertEquals(listOf(second, replacement), loaded(reopened.load()))
            assertEquals(
                DurableMutationResult.Applied,
                reopened.removeIfRevision(replacement.key, replacement.rowId),
            )
            assertEquals(listOf(second), loaded(reopened.load()))
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun loaded(result: DurableLoadResult<List<DurableQueueRow>>): List<DurableQueueRow> =
        when (result) {
            is DurableLoadResult.Loaded -> result.value
            DurableLoadResult.Failed -> throw AssertionError("Expected SQLite load to succeed")
        }
}
