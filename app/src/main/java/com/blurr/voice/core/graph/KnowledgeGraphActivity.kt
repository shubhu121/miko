package com.blurr.voice.core.graph

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.blurr.voice.R
import com.blurr.voice.core.Miko
import com.blurr.voice.core.timeline.TimelineEntry
import com.blurr.voice.core.ui.finishWithPop
import com.blurr.voice.core.ui.pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Renders Miko's knowledge graph, built from the on-device timeline: a central "You" node
 * linked to the apps and tasks it has seen, with cross-app edges between apps whose activity
 * co-occurred in time. Reachable from the Home screen.
 */
class KnowledgeGraphActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_knowledge_graph)

        val graphView = findViewById<KnowledgeGraphView>(R.id.graph_view)
        val empty = findViewById<TextView>(R.id.graph_empty)
        val back = findViewById<View>(R.id.graph_back)
        back.pressable()
        back.setOnClickListener { finishWithPop() }

        lifecycleScope.launch {
            val (nodes, edges) = withContext(Dispatchers.IO) { buildGraph() }
            if (nodes.size <= 1) {
                empty.visibility = View.VISIBLE
                graphView.visibility = View.GONE
            } else {
                empty.visibility = View.GONE
                graphView.setGraph(nodes, edges)
                
            }
        }
    }

    private suspend fun buildGraph(): Pair<List<GraphNode>, List<GraphEdge>> {
        val entries = runCatching { Miko.timeline.getRecent(300) }.getOrDefault(emptyList())
        val nodes = mutableListOf<GraphNode>()
        val edges = mutableListOf<GraphEdge>()

        val self = GraphNode("you", "You", weight = 6, type = GraphNodeType.SELF)
        nodes.add(self)

        // Top apps by frequency.
        val appCounts = entries.mapNotNull { it.packageName }
            .filter { it.isNotBlank() }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(8)
        val appNodeIds = mutableMapOf<String, String>() // package -> node id
        appCounts.forEach { (pkg, count) ->
            val id = "app:$pkg"
            appNodeIds[pkg] = id
            nodes.add(GraphNode(id, prettyAppName(pkg), weight = count.coerceAtMost(6), type = GraphNodeType.APP))
            edges.add(GraphEdge(self.id, id))
        }

        // Recent distinct tasks.
        entries.filter { it.type == "task" }
            .distinctBy { it.subtitle.ifBlank { it.title } }
            .take(5)
            .forEachIndexed { i, e ->
                val id = "task:$i"
                nodes.add(GraphNode(id, e.subtitle.ifBlank { e.title }, weight = 2, type = GraphNodeType.TASK))
                edges.add(GraphEdge(self.id, id))
            }

        // Cross-app edges: apps whose activity co-occurred within 10 minutes.
        val window = 10 * 60 * 1000L
        val sorted = entries.filter { it.packageName != null }.sortedBy { it.timestamp }
        val seenPairs = mutableSetOf<String>()
        for (i in sorted.indices) {
            var j = i + 1
            while (j < sorted.size && sorted[j].timestamp - sorted[i].timestamp <= window) {
                val a = sorted[i].packageName
                val b = sorted[j].packageName
                if (a != null && b != null && a != b && appNodeIds.containsKey(a) && appNodeIds.containsKey(b)) {
                    val key = listOf(a, b).sorted().joinToString("|")
                    if (seenPairs.add(key)) {
                        edges.add(GraphEdge(appNodeIds[a]!!, appNodeIds[b]!!))
                    }
                }
                j++
            }
        }
        return nodes to edges
    }

    private fun prettyAppName(pkg: String): String =
        pkg.substringAfterLast('.').replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
}
