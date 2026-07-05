package com.blurr.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.blurr.voice.utilities.SpeechCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blurr.voice.core.Miko
import com.blurr.voice.core.orchestration.OrchestratorService
import com.blurr.voice.core.suggestions.SuggestionService
import com.blurr.voice.core.ui.entrance
import com.blurr.voice.core.ui.pressable
import com.blurr.voice.core.ui.pushActivity
import com.blurr.voice.utilities.Logger
import com.blurr.voice.v2.AgentService
import com.blurr.voice.utilities.OnboardingManager
import com.blurr.voice.utilities.UserProfileManager
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Miko's Home — a calm, memory-first feed. Replaces the old Panda delta-symbol home as
 * the launcher / HOME nav destination. Surfaces the Phase-1 core services:
 *  - a greeting from [Miko.context]
 *  - the latest daily summary from [Miko.summary]
 *  - the live event [Miko.timeline]
 *  - global [Miko.search]
 *
 * The old assistant screen ([MainActivity]) is reached via "Talk to Miko".
 */
class MikoHomeActivity : BaseNavigationActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var greetingText: TextView
    private lateinit var summaryText: TextView
    private lateinit var searchInput: EditText
    private lateinit var feedRecycler: RecyclerView
    private lateinit var feedEmpty: TextView
    private lateinit var feedLabel: TextView
    private lateinit var suggestionCard: View
    private lateinit var suggestionTitle: TextView
    private lateinit var suggestionDetail: TextView
    private lateinit var suggestionAction: TextView
    private val adapter = MikoTimelineAdapter()

    /** Active search job, cancelled/replaced as the query changes. */
    private var searchJob: Job? = null
    private var currentQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Same auth + onboarding gate the old home enforced.
        auth = Firebase.auth
        val profileManager = UserProfileManager(this)
        if (auth.currentUser == null || !profileManager.isProfileComplete()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        val onboardingManager = OnboardingManager(this)
        if (!onboardingManager.isOnboardingCompleted()) {
            Logger.d(TAG, "Onboarding not completed. Relaunching permissions stepper.")
            startActivity(Intent(this, OnboardingPermissionsActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_miko_home)
        // Calm, edge-to-edge status bar that blends into the home background.
        @Suppress("DEPRECATION")
        window.statusBarColor = ContextCompat.getColor(this, R.color.miko_bg)

        greetingText = findViewById(R.id.greeting_text)
        summaryText = findViewById(R.id.summary_text)
        searchInput = findViewById(R.id.search_input)
        feedRecycler = findViewById(R.id.feed_recycler)
        feedEmpty = findViewById(R.id.feed_empty)
        feedLabel = findViewById(R.id.feed_label)

        feedRecycler.layoutManager = LinearLayoutManager(this)
        feedRecycler.adapter = adapter

        suggestionCard = findViewById(R.id.suggestion_card)
        suggestionTitle = findViewById(R.id.suggestion_title)
        suggestionDetail = findViewById(R.id.suggestion_detail)
        suggestionAction = findViewById(R.id.suggestion_action)

        val talkButton = findViewById<TextView>(R.id.talk_to_miko_button)
        val graphButton = findViewById<TextView>(R.id.graph_button)
        val micButton = findViewById<TextView>(R.id.mic_button)
        talkButton.setOnClickListener {
            pushActivity(Intent(this, MainActivity::class.java))
        }
        graphButton.setOnClickListener {
            pushActivity(Intent(this, com.blurr.voice.core.graph.KnowledgeGraphActivity::class.java))
        }
        micButton.setOnClickListener { startVoice() }
        val webSearchButton = findViewById<TextView>(R.id.web_search_button)
        webSearchButton.setOnClickListener { startVoiceWebSearch() }
        // Every tappable surface should "give" with a light haptic — the Apple touch.
        talkButton.pressable()
        graphButton.pressable()
        micButton.pressable()
        webSearchButton.pressable()
        suggestionAction.pressable()
        findViewById<View>(R.id.summary_card).pressable(pressedScale = 0.985f, haptics = false)

        setupSearch()
        observeTimeline()
        playEntrance()
    }

    /** Assembles the screen with a soft, staggered rise instead of snapping in. */
    private fun playEntrance() {
        findViewById<View>(R.id.greeting_row)?.entrance(delayMs = 0)
        findViewById<View>(R.id.search_input).entrance(delayMs = 60)
        findViewById<View>(R.id.summary_card).entrance(delayMs = 120)
        findViewById<View>(R.id.feed_label).entrance(delayMs = 180)
    }

    override fun onResume() {
        super.onResume()
        refreshGreeting()
        refreshSummary()
        loadSuggestions()
    }

    private val requestMicForWeb = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoiceWebSearch()
        else Toast.makeText(this, "Microphone permission is needed for voice search.", Toast.LENGTH_SHORT).show()
    }

    /** Voice web search: speak a query → Tavily → speak + show the answer. */
    private fun startVoiceWebSearch() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestMicForWeb.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        Toast.makeText(this, "Listening… say what to search for", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            SpeechCoordinator.getInstance(this@MikoHomeActivity).startListening(
                onResult = { query -> if (query.isNotBlank()) runWebSearch(query) },
                onError = { err ->
                    runOnUiThread {
                        Toast.makeText(this@MikoHomeActivity, "Didn't catch that ($err).", Toast.LENGTH_SHORT).show()
                    }
                },
                onListeningStateChange = {},
                onPartialResult = {}
            )
        }
    }

    private fun runWebSearch(query: String) {
        Toast.makeText(this, getString(R.string.miko_web_searching), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    com.blurr.voice.api.TavilyApi(com.blurr.voice.BuildConfig.TAVILY_API).searchText(query)
                }.getOrDefault("Sorry, the web search failed.")
            }
            runCatching { SpeechCoordinator.getInstance(this@MikoHomeActivity).speakToUser(result) }
            androidx.appcompat.app.AlertDialog.Builder(this@MikoHomeActivity)
                .setTitle("🌐 $query")
                .setMessage(result)
                .setPositiveButton("Done", null)
                .setNeutralButton("Stop Speaking") { _, _ ->
                    SpeechCoordinator.getInstance(this@MikoHomeActivity).stopSpeaking()
                }
                .show()
        }
    }

    /** Voice-first entry: wake the conversational agent to listen. */
    private fun startVoice() {
        try {
            if (!ConversationalAgentService.isRunning) {
                androidx.core.content.ContextCompat.startForegroundService(
                    this, Intent(this, ConversationalAgentService::class.java)
                )
            }
        } catch (e: Exception) {
            Logger.w(TAG, "startVoice failed: ${e.message}")
        }
    }

    /** Predictive suggestions: show the top one; its action runs only after confirmation. */
    private fun loadSuggestions() {
        lifecycleScope.launch {
            val top = runCatching { SuggestionService.currentSuggestions() }
                .getOrDefault(emptyList()).firstOrNull()
            if (top == null) {
                suggestionCard.visibility = View.GONE
                return@launch
            }
            suggestionTitle.text = top.title
            suggestionDetail.text = top.detail
            if (top.isActionable) {
                suggestionAction.visibility = View.VISIBLE
                suggestionAction.setOnClickListener { confirmAndRun(top.actionInstruction!!) }
            } else {
                suggestionAction.visibility = View.GONE
            }
            if (suggestionCard.visibility != View.VISIBLE) {
                suggestionCard.visibility = View.VISIBLE
                suggestionCard.entrance()
            }
        }
    }

    /** Autonomous task execution — gated behind explicit user approval (MIKO.md Phase 3). */
    private fun confirmAndRun(instruction: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.miko_confirm_run_title)
            .setMessage(getString(R.string.miko_confirm_run, instruction))
            .setPositiveButton(R.string.miko_run) { d, _ ->
                // Route through the orchestrator so multi-step goals get decomposed first.
                lifecycleScope.launch {
                    runCatching { OrchestratorService.orchestrate(this@MikoHomeActivity, instruction) }
                        .onFailure { AgentService.start(this@MikoHomeActivity, instruction) }
                }
                d.dismiss()
            }
            .setNegativeButton(R.string.miko_cancel, null)
            .show()
    }

    override fun getContentLayoutId(): Int = R.layout.activity_miko_home

    override fun getCurrentNavItem(): NavItem = NavItem.HOME

    private fun refreshGreeting() {
        val timeOfDay = runCatching { Miko.context.snapshot().timeOfDay }.getOrDefault("")
        val greetingRes = when (timeOfDay) {
            "morning" -> R.string.miko_greeting_morning
            "afternoon" -> R.string.miko_greeting_afternoon
            "evening" -> R.string.miko_greeting_evening
            "night" -> R.string.miko_greeting_night
            else -> R.string.miko_greeting_default
        }
        val name = auth.currentUser?.displayName?.substringBefore(' ')?.takeIf { it.isNotBlank() }
        greetingText.text = if (name != null) "${getString(greetingRes)}, $name" else getString(greetingRes)
    }

    private fun refreshSummary() {
        val latest = runCatching { Miko.summary.latestSummary() }.getOrNull()
        summaryText.text = if (latest.isNullOrBlank()) getString(R.string.miko_summary_empty) else latest
    }

    private fun setupSearch() {
        searchInput.doAfterTextChanged { editable ->
            val query = editable?.toString()?.trim().orEmpty()
            currentQuery = query
            if (query.isBlank()) {
                searchJob?.cancel()
                observeTimeline()
                feedLabel.setText(R.string.miko_timeline_label)
            } else {
                runSearch(query)
                feedLabel.setText(R.string.miko_search_hint)
            }
        }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else false
        }
    }

    /** Live timeline: collect the Room-backed flow while the screen is visible. */
    private var timelineJob: Job? = null
    private fun observeTimeline() {
        timelineJob?.cancel()
        timelineJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Miko.timeline.observeAll().collect { entries ->
                    // A search may have started while we were suspended; don't clobber it.
                    if (currentQuery.isBlank()) {
                        submit(entries.map { FeedRow.from(it) })
                    }
                }
            }
        }
    }

    private fun runSearch(query: String) {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            val hits = runCatching { Miko.search.search(query) }.getOrDefault(emptyList())
            // Ignore stale results if the query changed while we awaited.
            if (currentQuery == query) {
                submit(hits.map { FeedRow.from(it) }, emptyText = getString(R.string.miko_search_empty))
            }
        }
    }

    private fun submit(rows: List<FeedRow>, emptyText: String = getString(R.string.miko_timeline_empty)) {
        adapter.updateItems(rows)
        if (rows.isEmpty()) {
            feedEmpty.text = emptyText
            feedEmpty.visibility = View.VISIBLE
            feedRecycler.visibility = View.GONE
        } else {
            feedEmpty.visibility = View.GONE
            feedRecycler.visibility = View.VISIBLE
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    companion object {
        private const val TAG = "MikoHomeActivity"
    }
}
