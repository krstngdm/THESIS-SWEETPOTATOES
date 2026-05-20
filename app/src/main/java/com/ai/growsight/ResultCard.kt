package com.ai.growsight.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ai.growsight.R
import com.ai.growsight.ai.CropInterpretation

/**
 * ResultCard
 *
 * Binds a [CropInterpretation] to the views inside item_conversation_card.xml.
 *
 * Call [bind] from your RecyclerView adapter's onBindViewHolder, or wherever
 * you inflate the card and have a reference to the root view.
 *
 * The view mode toggle (Summary ↔ Full detail) is handled entirely here —
 * no state needs to be stored in the adapter or fragment.
 *
 * harvestTime is sourced from InterpretationEngine.scenarioHarvestTime() via
 * CropInterpretation.harvestTime — no hardcoded strings live in this file.
 */
object ResultCard {

    fun bind(root: View, interpretation: CropInterpretation) {
        val ctx = root.context

        // ── Scenario badge ─────────────────────────────────────────────────
        val scenarioBadge = root.findViewById<TextView>(R.id.scenarioBadge)
        if (!interpretation.scenarioLabel.isNullOrBlank()) {
            scenarioBadge.text = interpretation.scenarioLabel.replace("_", " ")
            scenarioBadge.visibility = View.VISIBLE
        } else {
            scenarioBadge.visibility = View.GONE
        }

        // ── Stage label ────────────────────────────────────────────────────
        root.findViewById<TextView>(R.id.stageLabel).text = interpretation.stage

        // ── Stage dot color ────────────────────────────────────────────────
        val dot = root.findViewById<View>(R.id.stageColorDot)
        dot.background = ContextCompat.getDrawable(
            ctx, when (interpretation.stageColor) {
                "green"  -> R.drawable.circle_green
                "yellow" -> R.drawable.circle_yellow
                "red"    -> R.drawable.circle_red
                else     -> R.drawable.circle_gray
            }
        )

        // ── Confidence chip ────────────────────────────────────────────────
        val chip = root.findViewById<TextView>(R.id.confidenceChip)
        chip.text = "${interpretation.confidencePercent}%"
        chip.background = ContextCompat.getDrawable(
            ctx, when {
                interpretation.confidencePercent >= 80 -> R.drawable.rounded_corner_green
                interpretation.confidencePercent >= 65 -> R.drawable.rounded_corner_orange
                else                                    -> R.drawable.rounded_corner_red
            }
        )

        // ── Low confidence warning ─────────────────────────────────────────
        root.findViewById<TextView>(R.id.lowConfidenceWarning).visibility =
            if (interpretation.lowConfidenceWarning) View.VISIBLE else View.GONE

        // ── Harvest time ───────────────────────────────────────────────────
        // Sourced from InterpretationEngine.scenarioHarvestTime(scenarioId)
        // stored in CropInterpretation.harvestTime — never hardcoded here.
        root.findViewById<TextView>(R.id.harvestTime).text = "→ ${interpretation.harvestTime}"

        // ── Recommendations (bullet list) ──────────────────────────────────
        val recContainer = root.findViewById<LinearLayout>(R.id.recommendationContainer)
        recContainer.removeAllViews()
        interpretation.recommendations.forEach { rec ->
            val item = TextView(ctx).apply {
                text = rec
                textSize = 13f
                setTextColor(Color.parseColor("#212121"))
                setPadding(0, 6, 0, 0)
                setLineSpacing(0f, 1.4f)
            }
            recContainer.addView(item)
        }

        // ── Interpretation summary (paragraph / full detail) ───────────────
        val summaryText = root.findViewById<TextView>(R.id.interpretationSummaryText)
        summaryText.text = interpretation.interpretationSummary ?: ""

        // ── View mode toggle wiring ────────────────────────────────────────
        val toggleSummary    = root.findViewById<TextView>(R.id.toggleSummary)
        val toggleFullDetail = root.findViewById<TextView>(R.id.toggleFullDetail)

        // Reset to Summary tab on every bind (avoids stale state when recycled)
        applyViewMode(root, isSummary = true, toggleSummary, toggleFullDetail)

        toggleSummary.setOnClickListener {
            applyViewMode(root, isSummary = true, toggleSummary, toggleFullDetail)
        }
        toggleFullDetail.setOnClickListener {
            applyViewMode(root, isSummary = false, toggleSummary, toggleFullDetail)
        }

        // ── Weather summary ────────────────────────────────────────────────
        // Optional: if you add a weatherSummaryText TextView to the XML,
        // tag it with android:tag="weatherSummaryText" and it will auto-bind.
        root.findViewWithTag<TextView>("weatherSummaryText")?.apply {
            if (!interpretation.weatherSummary.isNullOrBlank()) {
                text = interpretation.weatherSummary
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Swap active tab styling + show/hide content panels
    // ─────────────────────────────────────────────────────────────────────
    private fun applyViewMode(
        root: View,
        isSummary: Boolean,
        toggleSummary: TextView,
        toggleFullDetail: TextView
    ) {
        val recContainer = root.findViewById<LinearLayout>(R.id.recommendationContainer)
        val summaryText  = root.findViewById<TextView>(R.id.interpretationSummaryText)

        if (isSummary) {
            toggleSummary.setBackgroundResource(R.drawable.toggle_active_bg)
            toggleSummary.setTextColor(Color.parseColor("#212121"))
            toggleSummary.setTypeface(null, Typeface.BOLD)

            toggleFullDetail.setBackgroundColor(Color.TRANSPARENT)
            toggleFullDetail.setTextColor(Color.parseColor("#9E9E9E"))
            toggleFullDetail.setTypeface(null, Typeface.NORMAL)

            recContainer.visibility = View.VISIBLE
            summaryText.visibility  = View.GONE
        } else {
            toggleFullDetail.setBackgroundResource(R.drawable.toggle_active_bg)
            toggleFullDetail.setTextColor(Color.parseColor("#212121"))
            toggleFullDetail.setTypeface(null, Typeface.BOLD)

            toggleSummary.setBackgroundColor(Color.TRANSPARENT)
            toggleSummary.setTextColor(Color.parseColor("#9E9E9E"))
            toggleSummary.setTypeface(null, Typeface.NORMAL)

            recContainer.visibility = View.GONE
            summaryText.visibility  = View.VISIBLE
        }
    }
}