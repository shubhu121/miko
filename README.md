<div align="center">

# 🐾 Miko

**An AI personal operating layer for Android.**

Miko isn't a chatbot. It's a persistent, on-device AI layer that understands you, remembers
what matters, observes context across your phone (with permission), and proactively helps —
so the device gradually becomes *aware* of how you work.

</div>

---

## Table of Contents

- [What is Miko](#what-is-miko)
- [Feature Overview](#feature-overview)
- [Architecture](#architecture)
- [The `core/` Services](#the-core-services)
- [Technical Stack](#technical-stack)
- [Technical Choices & Rationale](#technical-choices--rationale)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
- [Permissions](#permissions)
- [Privacy](#privacy)
- [Roadmap](#roadmap)

---

## What is Miko

Miko began as **Panda**, an "AI phone operator" that executed one-shot commands via Android's
Accessibility APIs. Miko re-frames that capability inside a larger idea:

> **Panda executed commands. Miko understands people.**

Every architectural decision serves five pillars: **Context · Memory · Personalization ·
Proactivity · Privacy.** Instead of *ask → answer*, Miko runs a continuous loop:

```
Device events → Context collection → Memory → Reasoning → Automation → Helpful suggestions
```

It draws inspiration from Siri's accessibility, Nothing's contextual Essential Space, mymind's
automatic organization, and Google Now's proactivity — with long-term memory powered by a
knowledge graph.

---

## Feature Overview

**Foundation**
- 🧭 **Context Engine** — normalized snapshot of foreground app, clipboard, battery, connectivity, time of day.
- 🧠 **Memory** — local-first semantic memory plus a cloud knowledge graph.
- 📰 **Activity Timeline** — a private, on-device record of notifications, apps, calls, messages, and more.
- 🔎 **Unified Search** — one query across memories + timeline.
- 📅 **Daily Summary** — a warm, LLM-written recap of your day.
- 📝 **Memory Notepad** — rich markdown notes (bold/italic/highlight/code + syntax highlighting), inline images, and voice memos.

**Intelligence**
- 🗺️ **Planner** — intent → memory/context retrieval → structured plan → reflection.
- ⚙️ **Automation Rules** — event-driven "when X happens, do Y".
- ⏰ **Reminder Intelligence** — schedule reminders and let Miko propose them.
- 🖼️ **Screenshot Understanding** — Gemini vision reads and remembers your screenshots.
- 🕸️ **Knowledge Graph Visualization** — an on-device graph of how your apps and activity connect.
- 🤝 **Multi-agent Orchestration** — decompose complex goals into ordered subtasks.
- ✨ **Predictive Suggestions & Workflow Learning** — learns routines and surfaces timely help.

**Interaction**
- 🎙️ **Voice-first** — Vosk wake word, speech-to-text, and natural Deepgram speech responses.
- 🌐 **Voice Web Search** — speak a question, Miko searches the web via Tavily and answers aloud.
- 📱 **Phone automation** — the agent operates apps for you through Accessibility.

---

## Architecture

```
                                 User
                     Voice · Text · Screen · Device events
                                   │
                          ┌────────▼─────────┐
                          │   Intent Engine   │   ConversationalAgentService / v2 Agent
                          └────────┬─────────┘
                                   │
                       ┌───────────▼────────────┐
                       │  Context Collection     │  ContextService
                       │  Apps · Notifications ·  │  monitors/ (calls, SMS)
                       │  Clipboard · Sensors ·   │  NotificationListener
                       │  Calls · Messages        │
                       └───────────┬────────────┘
                                   │  MikoEvent
                          ┌────────▼─────────┐
                          │     EventBus      │  (SharedFlow, app-wide)
                          └────────┬─────────┘
              ┌────────────────────┼─────────────────────┐
     ┌────────▼───────┐   ┌────────▼────────┐    ┌────────▼────────┐
     │    Timeline     │   │   Ingestion      │    │   Automation    │
     │  (Room, 30d)    │   │  → Cognee graph  │    │  (rule engine)  │
     └────────┬───────┘   └────────┬────────┘    └─────────────────┘
              │                    │
     ┌────────▼────────────────────▼────────┐
     │              Memory Service            │
     │  Local-first (Room + Gemini embeds)    │  authoritative
     │  + Cognee knowledge graph (cloud)      │  enrichment
     └────────┬───────────────────────────────┘
              │
     ┌────────▼────────┐    ┌──────────────┐    ┌────────────────┐
     │   Planner /      │───▶│  Tool layer   │───▶│  Execution &   │
     │   Orchestrator   │    │  (agent acts) │    │  Automation    │
     └─────────────────┘    └──────────────┘    └────────┬───────┘
                                                          │
                                                  Android Accessibility
```

Everything is wired at a single **composition root** — [`core/Miko.kt`](app/src/main/java/com/blurr/voice/core/Miko.kt) —
initialized once from `MyApplication`. It exposes `Miko.context`, `Miko.memory`, `Miko.timeline`,
`Miko.search`, `Miko.summary`, and the shared `Miko.events` bus, and starts the background
services (ingestion, automation, reminders, screenshot understanding, daily-summary worker).

The design deliberately favors **loose coupling**: producers publish `MikoEvent`s to the
`EventBus`; consumers (timeline, ingestion, automation) react independently. This keeps modules
reusable and testable, and leaves room for future desktop/wearable clients on the same backend.

---

## The `core/` Services

| Package | Responsibility |
|---|---|
| `core/Miko.kt` | Composition root; wires and starts everything. |
| `core/events/` | `EventBus` (SharedFlow) + `MikoEvent` sealed hierarchy. |
| `core/context/` | `ContextService` — device snapshot + signal → event emission. |
| `core/memory/` | `MemoryRepository` (local-first + Cognee) + `CrossAppMemory`. |
| `core/timeline/` | Room-backed chronological activity log (30-day retention). |
| `core/search/` | `UnifiedSearchService` — semantic memory + timeline. |
| `core/summary/` | `DailySummaryWorker` + store (LLM daily recap at 21:00). |
| `core/ingestion/` | `IngestionService` — EventBus → Cognee bridge (batched, `node_set`-tagged). |
| `core/planner/` | `PlannerService` — reasoning engine (intent → plan → reflect). |
| `core/automation/` | `AutomationService` + `AutomationRule` — event-driven rules. |
| `core/reminders/` | `ReminderService` + receiver (AlarmManager → notification). |
| `core/screenshot/` | `ScreenshotUnderstandingService` — Gemini vision on screenshots. |
| `core/orchestration/` | `OrchestratorService` — multi-step goal decomposition. |
| `core/suggestions/` | `SuggestionService` — proactive Home suggestions. |
| `core/learning/` | `RoutineLearner` — detects recurring routines from the timeline. |
| `core/monitors/` | `CallStateReceiver` (missed calls), `SmsReceiver`. |
| `core/notes/` | Local file `NoteStore`, `AudioRecorder`, `MarkdownRenderer`. |
| `core/graph/` | `KnowledgeGraphActivity` + custom Canvas `KnowledgeGraphView`. |
| `core/ui/` | iOS-inspired interaction/motion system (haptics, press-scale, transitions). |

---

## Technical Stack

- **Language:** Kotlin, Coroutines + Flow
- **Platform:** Android (minSdk 24, targetSdk 35)
- **UI:** Android Views + Material Components; a small iOS-flavored motion layer (`core/ui`)
- **Persistence:** Room (timeline, memory), SharedPreferences, local files (notes/audio/images)
- **Background:** WorkManager, foreground services, AlarmManager, BroadcastReceivers
- **LLM:** Google **Gemini** (`gemini-2.5-flash-lite`) — text + vision
- **Knowledge graph:** **Cognee** cloud
- **Text-to-speech:** **Deepgram** Aura-2
- **Speech-to-text / wake word:** **Vosk** (on-device)
- **Web search:** **Tavily**
- **Auth / infra:** Firebase (Auth, Firestore, Remote Config, Crashlytics)
- **Markdown:** Markwon (notepad rendering)

---

## Technical Choices & Rationale

### Why Cognee (knowledge graph)
Miko's value is *connected* memory — "this screenshot belongs to Project Alpha", "you already
solved a similar issue" — not a flat list of notes. Building that ourselves would mean writing a
chunker, an embedder, entity/relationship extraction, and a graph store on-device.

**Cognee does all of that server-side.** We send raw text to `/add`, call `/cognify`, and Cognee
handles chunking, embedding, entity extraction, and graph construction. That let us stay focused
on the product instead of reinventing a RAG/graph pipeline. Crucially, Miko is **local-first**:
`MemoryManager` (Room + Gemini embeddings) is the authoritative on-device store for offline,
private semantic search; Cognee is best-effort *enrichment* layered on top. The `IngestionService`
feeds salient timeline activity into the graph under typed `node_set`s (`notes`, `notifications`,
`calls`, `messages`, `emails`, …) so cross-app relationships emerge.

> We deliberately did **not** build our own embedder/chunker — that is exactly the work Cognee
> owns. What Miko adds is the *ingestion* layer that decides what is worth remembering.

### Why Tavily (web search)
Agents need live facts, but raw search engines return HTML meant for humans. **Tavily is a
search API built for LLMs**: a single call returns a concise, ready-to-use answer plus ranked
sources — no scraping, no HTML parsing, no rate-limit gymnastics. It's exposed to the agent as a
first-class `web_search` action and as a one-tap **voice web search** on the Home screen.

### Why Deepgram (text-to-speech)
A personal assistant lives or dies on how it *sounds*. Google's stock TTS felt robotic. **Deepgram
Aura-2** offers low-latency, natural voices and streams **raw LINEAR16 PCM (24 kHz)** that we feed
directly into an `AudioTrack` pipeline — enabling smart chunking and near-instant first-audio while
later sentences synthesize in the background. It replaced the previous Google TTS path entirely.

### Why Vosk (speech-to-text & wake word)
Wake-word detection means *always listening*, which is a privacy minefield if audio leaves the
device. **Vosk runs 100% on-device**, needs no API key, and the model ships bundled in app assets —
so ambient audio is never streamed to a cloud for wake detection. It replaced the previous
Picovoice Porcupine engine, removing a paid dependency and an access-key setup step.

### Why Gemini (reasoning & vision)
Miko needs one model family that is fast, cheap at scale, and **multimodal** (screenshots demand
vision). Gemini fits, and the `gemini-2.5-flash-lite` tier gives the best latency/quota trade-off
for a consumer app. The client supports two modes: a **direct** on-device SDK call using rotated
`GEMINI_API_KEYS`, or an optional **secure Cloud Function proxy** (`GCLOUD_PROXY_URL`) that keeps
the real key off the device for public distribution.

### Why local-first everywhere
Memory, the timeline, notes, and voice recordings are stored on-device and treated as the source
of truth. The cloud is an enhancement, never a requirement — this is both a privacy stance and a
reliability one (the app keeps working offline, and a note can never fail to save because of a
network hiccup).

---

## Project Structure

```
app/src/main/
├── java/com/blurr/voice/
│   ├── core/                 # Miko's operating layer (see table above)
│   ├── v2/                   # Phone-automation agent (perception, actions, LLM)
│   ├── api/                  # Service clients: GeminiApi, DeepgramTts, MemoryService (Cognee), TavilyApi
│   ├── data/                 # Room memory (MemoryManager, DAOs)
│   ├── triggers/             # Legacy trigger system + NotificationListener
│   ├── utilities/            # TTSManager, SpeechCoordinator, managers, helpers
│   ├── MikoHomeActivity.kt   # The memory-first Home screen (launcher)
│   ├── MainActivity.kt       # Assistant/“Talk to Miko” screen
│   ├── NoteEditorActivity.kt # Rich markdown notepad
│   └── ConversationalAgentService.kt
├── assets/prompts/           # system_prompt.md
└── res/                      # Layouts, drawables (miko_* brand assets), strings
MIKO.md                       # Product vision & transformation guide
privacy-policy.html           # Public privacy policy
```

---

## Getting Started

**Prerequisites:** Android Studio, JDK 17, an Android SDK, and a device/emulator (API 24+).

1. **Clone** and open in Android Studio.
2. **Configure secrets** in `local.properties` (git-ignored):

   ```properties
   # Reasoning & vision (comma-separate multiple keys; they are rotated)
   GEMINI_API_KEYS=your_gemini_key_1,your_gemini_key_2

   # Text-to-speech
   DEEPGRAM_API_KEY=your_deepgram_key

   # Cloud knowledge graph
   COGNEE_API_KEY=your_cognee_key
   COGNEE_BASE_URL=https://tenant-xxxx.aws.cognee.ai
   COGNEE_TENANT_ID=your_tenant_id
   COGNEE_USER_ID=your_user_id

   # Web search
   TAVILY_API=your_tavily_key

   # Optional: route Gemini through a Cloud Function proxy instead of direct keys
   GCLOUD_PROXY_URL=
   GCLOUD_PROXY_URL_KEY=
   ```

   > A commented template is provided in `local.properties.template`. If any key is blank, the
   > corresponding feature degrades gracefully (e.g., no Cognee key → memory stays fully local).

3. **Build & run:**

   ```bash
   ./gradlew :app:assembleDebug        # debug APK
   ./gradlew :app:installDebug         # install on a connected device
   ./gradlew :app:assembleRelease      # minified release (R8)
   ```

   On first launch, grant the requested permissions to unlock the corresponding features.

---

## Permissions

Miko requests permissions **only for the features you use**; each degrades gracefully if denied.

| Permission | Enables |
|---|---|
| Accessibility Service | Read screen content and automate apps |
| `RECORD_AUDIO` | Voice commands, wake word, voice notes |
| Notification Listener | Timeline + email/calendar categorization + automations |
| `READ_PHONE_STATE` | Missed-call detection |
| `RECEIVE_SMS` | Incoming-message detection |
| `READ_MEDIA_IMAGES` | Screenshot understanding + note images |
| `SYSTEM_ALERT_WINDOW` | On-screen visual feedback |
| `SCHEDULE_EXACT_ALARM` / `POST_NOTIFICATIONS` | Reminders |
| `QUERY_ALL_PACKAGES` | Identify apps by name |

---

## Privacy

Local-first by design: memory, timeline, notes, and recordings live on-device and are the source
of truth. Cloud services (Gemini, Cognee, Deepgram, Tavily, Firebase) receive only what a feature
needs, and Vosk wake-word detection never leaves the device. Full details are in
[`privacy-policy.html`](privacy-policy.html).

---

## Roadmap

- **Done:** Context engine, memory + knowledge graph, event bus, unified search, timeline, daily
  summary, planner, automation, reminders, screenshot understanding, cross-app memory, graph
  visualization, orchestration, predictive suggestions, workflow learning, voice-first + web search.
- **Next:** richer proactive assistance, a runtime permission-onboarding flow for the device
  monitors, knowledge-graph fetched from Cognee, and multi-device (desktop/wearable) clients on the
  same backend.

---

<div align="center">
<sub>Built on the Panda AI phone-operator foundation • See <a href="MIKO.md">MIKO.md</a> for the full product vision.</sub>
</div>
