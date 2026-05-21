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
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewOutlineProvider
import com.ai.growsight.ai.AnomalyFlag

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
        bindAlertBanner(root, interpretation)
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

    private fun bindAlertBanner(root: View, interpretation: CropInterpretation) {
        val banner = root.findViewWithTag<LinearLayout>("anomalyBanner") ?: return
        val flags  = interpretation.anomalyFlags

        android.util.Log.d("ResultCard", "bindAlertBanner: flags=${flags.size}, banner=$banner")

        if (flags.isEmpty()) {
            banner.visibility = View.GONE
            return
        }

        // Highest severity wins the banner color
        val highest = flags.minByOrNull {
            when (it.severity) { "critical" -> 0; "high" -> 1; "medium" -> 2; else -> 3 }
        } ?: return

        val (bgHex, textHex, icon) = when (highest.severity) {
            "critical" -> Triple("#B71C1C", "#FFFFFF", "🔴")
            "high"     -> Triple("#BF360C", "#FFFFFF", "🟠")
            "medium"   -> Triple("#F57F17", "#212121", "🟡")
            else       -> Triple("#1565C0", "#FFFFFF", "🔵")
        }

        banner.setBackgroundColor(Color.parseColor(bgHex))
        banner.visibility = View.VISIBLE
        banner.removeAllViews()

        val ctx   = root.context
        val count = flags.size
        val sevLabel = highest.severity.replaceFirstChar { it.uppercase() }

        val label = TextView(ctx).apply {
            text = "$icon  $sevLabel · $count ${if (count == 1) "issue" else "issues"} detected — see below"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(textHex))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        val chevron = TextView(ctx).apply {
            text = "›"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(textHex))
        }

        banner.addView(label)
        banner.addView(chevron)
    }

    private fun buildAnomalyRow(ctx: Context, flag: AnomalyFlag): View {
        val (stripHex, bgHex, badgeHex, badgeTextHex) = when (flag.severity) {
            "critical" -> arrayOf("#B71C1C", "#FFF5F5", "#B71C1C", "#FFFFFF")
            "high"     -> arrayOf("#E64A19", "#FBE9E7", "#E64A19", "#FFFFFF")
            "medium"   -> arrayOf("#F57F17", "#FFFDE7", "#F57F17", "#212121")
            else       -> arrayOf("#1565C0", "#E8EAF6", "#1565C0", "#FFFFFF")
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { lp -> lp.setMargins(0, 10, 0, 0) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = 10f
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            elevation = 2f
        }

        // Left severity strip
        val strip = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(6, LinearLayout.LayoutParams.MATCH_PARENT).also { lp ->
                lp.setMargins(0, 0, 0, 0)
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor(stripHex))
                cornerRadius = 10f
            }
        }

        // Content block
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(14, 12, 14, 12)
        }

        // Header row: badge + severity dot
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val badge = TextView(ctx).apply {
            text = flag.badgeLabel
            textSize = 10.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor(badgeTextHex))
            setPadding(12, 4, 12, 4)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(badgeHex))
                cornerRadius = 24f
            }
        }

        val sevDot = TextView(ctx).apply {
            text = when (flag.severity) { "critical" -> " 🔴"; "high" -> " 🟠"; "medium" -> " 🟡"; else -> " 🔵" }
            textSize = 11f
            setPadding(8, 0, 0, 0)
        }

        headerRow.addView(badge)
        headerRow.addView(sevDot)
        content.addView(headerRow)

        // Detail text
        content.addView(TextView(ctx).apply {
            text = flag.detail
            textSize = 12.5f
            setTextColor(Color.parseColor("#424242"))
            setPadding(0, 8, 0, 0)
            setLineSpacing(0f, 1.4f)
        })

        // Thin divider
        content.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).also { lp -> lp.setMargins(0, 10, 0, 8) }
            setBackgroundColor(Color.parseColor("#E0E0E0"))
        })

        // "What to do" label
        content.addView(TextView(ctx).apply {
            text = "💡 What to do"
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#616161"))
        })

        // Suggestion text
        content.addView(TextView(ctx).apply {
            text = flag.suggestion
            textSize = 12.5f
            setTextColor(Color.parseColor("#212121"))
            setPadding(0, 5, 0, 0)
            setLineSpacing(0f, 1.4f)
        })

        row.addView(strip)
        row.addView(content)
        return row
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