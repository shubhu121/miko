package com.blurr.voice.core.graph

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class GraphNodeType { SELF, APP, TOPIC, TASK }

data class GraphNode(val id: String, val label: String, val weight: Int, val type: GraphNodeType)
data class GraphEdge(val from: String, val to: String)

/**
 * A lightweight, dependency-free knowledge-graph renderer (MIKO.md Phase 2 "Knowledge Graph
 * Visualization"). Lays the "You" node at the centre with topic/app/task nodes on a ring
 * around it, drawing edges (including cross-app connections) beneath the nodes. Pure Canvas —
 * no WebView or external graph library.
 */
class KnowledgeGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var nodes: List<GraphNode> = emptyList()
    private var edges: List<GraphEdge> = emptyList()
    private val positions = mutableMapOf<String, FloatArray>() // id -> [x, y]

    /** 0→1 reveal progress; nodes scale/fade in staggered from the centre outward. */
    private var appear = 0f
    private val appearInterpolator = PathInterpolator(0.2f, 0.0f, 0.0f, 1f)
    private var appearAnimator: ValueAnimator? = null

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E2A40")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F3EEFF")
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    fun setGraph(nodes: List<GraphNode>, edges: List<GraphEdge>) {
        this.nodes = nodes
        this.edges = edges
        layoutNodes()
        startAppearAnimation()
    }

    private fun startAppearAnimation() {
        appearAnimator?.cancel()
        appearAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            interpolator = appearInterpolator
            addUpdateListener {
                appear = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutNodes()
    }

    private fun layoutNodes() {
        positions.clear()
        if (nodes.isEmpty() || width == 0 || height == 0) return
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.36f

        val self = nodes.firstOrNull { it.type == GraphNodeType.SELF }
        self?.let { positions[it.id] = floatArrayOf(cx, cy) }

        val ring = nodes.filter { it.type != GraphNodeType.SELF }
        ring.forEachIndexed { i, node ->
            val angle = (2.0 * Math.PI * i / ring.size).toFloat()
            // Alternate ring distance a little so labels overlap less.
            val r = radius * if (i % 2 == 0) 1f else 0.72f
            positions[node.id] = floatArrayOf(cx + r * cos(angle), cy + r * sin(angle))
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (nodes.isEmpty()) return

        // Edges fade in with the overall reveal, beneath the nodes.
        edgePaint.alpha = (appear * 255).toInt().coerceIn(0, 255)
        edges.forEach { edge ->
            val a = positions[edge.from] ?: return@forEach
            val b = positions[edge.to] ?: return@forEach
            canvas.drawLine(a[0], a[1], b[0], b[1], edgePaint)
        }

        nodes.forEachIndexed { index, node ->
            val p = positions[node.id] ?: return@forEachIndexed
            // Stagger each node's pop-in across the reveal timeline.
            val local = ((appear * (nodes.size + 4)) - index).coerceIn(0f, 1f)
            if (local <= 0f) return@forEachIndexed
            nodePaint.color = colorFor(node.type)
            nodePaint.alpha = (local * 255).toInt().coerceIn(0, 255)
            val r = radiusFor(node) * local
            canvas.drawCircle(p[0], p[1], r, nodePaint)
            labelPaint.alpha = (local * 255).toInt().coerceIn(0, 255)
            canvas.drawText(node.label.take(18), p[0], p[1] + radiusFor(node) + 30f, labelPaint)
        }
        // Reset alphas so paints are clean for the next frame.
        nodePaint.alpha = 255
        labelPaint.alpha = 255
    }

    private fun radiusFor(node: GraphNode): Float = when (node.type) {
        GraphNodeType.SELF -> 46f
        else -> (22f + node.weight * 4f).coerceAtMost(48f)
    }

    private fun colorFor(type: GraphNodeType): Int = when (type) {
        GraphNodeType.SELF -> Color.parseColor("#FF89A8")  // blush — the user
        GraphNodeType.APP -> Color.parseColor("#B084F5")   // lavender
        GraphNodeType.TOPIC -> Color.parseColor("#84C5F5")
        GraphNodeType.TASK -> Color.parseColor("#F5C784")
    }
}
