package app.shiftlit.driver

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import org.json.JSONObject

/**
 * Watches the configured driver apps' windows, extracts offer cards, scores
 * them against the user's rules, shows the floating verdict, and optionally
 * taps accept/decline when automation toggles are on.
 *
 * Everything runs on-device — no screen content or offer data leaves the phone.
 */
class ShiftlitAccessibilityService : AccessibilityService() {

    private lateinit var settings: SettingsRepository
    private lateinit var overlay: OverlayController

    private var lastFingerprint = ""
    private var lastSeenAt = 0L
    private var lastScanAt = 0L
    private var lastRadarTapAt = 0L
    private var lastStalledTapAt = 0L

    /** Payout of the Spark order whose card was opened for a reject — 0 when
     *  none. A scan of the opened detail must reject, not open it again. */
    private var sparkOpenedPayout = 0
    private var scanToken = 0
    private var activeOffer: ScoredOffer? = null

    /** Live point-at-the-card teach session, if one is running. */
    private var teach: TeachOverlay? = null
    private var teachSession: TeachSession? = null

    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null
    private var hideToken = ""
    private var tts: TextToSpeech? = null
    private var localDb: LocalDb? = null
    private var countdownTicker: Runnable? = null

    /** One extra decline tap on a freshly read screen — see [scheduleDeclineRetry]. */
    private var declineRetry: Runnable? = null
    private var declineRetryKey = ""
    private var declineRetried = false

    /**
     * Safety net for offer windows that draw without emitting an accessibility
     * event. Samsung/Uber can add the Trip Radar card silently; waiting for a
     * contentChanged event means the card is gone before the first read.
     */
    private var pollRunnable: Runnable? = null

    /** Driver apps Shiftlit reads — the rest of the event stream is only used to
     *  notice that the driver app left the screen. */
    private val watched: Set<String> by lazy {
        resources.getStringArray(R.array.watched_packages).toSet()
    }

    // Auto-shift tracking state
    private var lastDriverAppAt = 0L
    private var idleStopCheck: Runnable? = null
    private val loggedCompletions = mutableSetOf<String>()
    /** One undo hint per failed AI mapping, not one per retry frame. */
    private var lastAiMissKey = ""

    companion object {
        /** Auto-stop shift tracking after this long with no driver-app events. */
        private const val IDLE_STOP_MS = 30 * 60_000L

        /** Frames can flicker mid-animation; give an offer this long to
         *  reappear before hiding the overlay. */
        private const val MISS_GRACE_MS = 2_000L

        /** Re-read delays for an offer-like frame that didn't parse yet —
         *  cards and overlays animate in, so the first read can be just a
         *  header before the payout and distance are laid out. */
        private val RETRY_DELAYS_MS = longArrayOf(250L, 700L, 1_500L, 2_500L)

        /** A verdict never outlives this, even if the card behind it lingers. */
        private const val MAX_OVERLAY_MS = 30_000L

        /** How long a decline gets to clear the card before the same offer,
         *  re-read live, may be declined by a different control. */
        private const val DECLINE_RETRY_MS = 1_200L

        /** Don't tap a stalled list's refresh on every scan while its snackbar
         *  lingers; a reload needs a moment to produce cards. */
        private const val STALLED_REFRESH_MS = 4_000L

        /** Spark's order detail after its payout was tapped. */
        private const val SPARK_PKG = "com.walmart.sparkdriver"
        private const val SPARK_OPEN_MS = 900L
        /** Extra reads of a detail that hadn't drawn yet; then give up. */
        private const val SPARK_READ_ATTEMPTS = 2
        private const val SPARK_REREAD_MS = 1_200L

        /** Package name of the synthetic offer from the Assist screen. */
        private const val TEST_PKG = "app.shiftlit.test"

        /** Live instance while the service is enabled — null otherwise. */
        @Volatile var instance: ShiftlitAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = SettingsRepository(this)
        overlay = OverlayController(this)
        localDb = LocalDb(this)
        tts = TextToSpeech(this) { /* ready */ }
        instance = this
        startWindowPoll()
    }

    /**
     * Poll the interactive windows once a second while monitoring is on. The
     * event-driven path still does the real work; this only catches overlays
     * that never produce a usable event. It reads roots and scores them, so it
     * stays idle when no watched app is visible.
     */
    private fun startWindowPoll() {
        if (pollRunnable != null) return
        val r = object : Runnable {
            override fun run() {
                if (!settings.enabled || teachSession != null) {
                    handler.postDelayed(this, 1_000)
                    return
                }
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isInteractive) {
                    handler.postDelayed(this, 1_500)
                    return
                }
                val now = System.currentTimeMillis()
                val pkg = visibleWatchedPackage()
                if (pkg != null) {
                    onDriverAppSeen(now)
                    if (now - lastScanAt >= 700) {
                        lastScanAt = now
                        scan(pkg, now, attempt = 0, token = ++scanToken)
                    }
                }
                handler.postDelayed(this, 1_000)
            }
        }
        pollRunnable = r
        handler.postDelayed(r, 1_000)
    }

    private fun stopWindowPoll() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
    }

    /**
     * Synthetic test offer — exercises the whole pipeline: score → overlay →
     * countdown → gesture dispatch → voice. The accept node points at a
     * screen-margin dead zone so the auto-accept tap provably fires without
     * touching anything real; nothing is logged (see recordAction).
     */
    fun testOffer() {
        handler.post {
            // Nothing runs while monitoring is off — including the dry run.
            if (!settings.enabled) {
                toast("Monitoring is off — turn it on first")
                return@post
            }
            val dead = NodeRef(Bounds(8, 1150, 14, 1190), "test")
            val o = DetectedOffer(
                appPackage = TEST_PKG,
                payoutCents = (12.50 * 100).toInt(),
                tipCents = 200,
                distanceKm = 4.2,
                durationMin = 18.0,
                extraDistanceKm = null,
                acceptNode = dead,
                declineNode = dead,
                rawTexts = listOf("Test offer"),
                currencySymbol = settings.currencySymbol,
                currencyCode = settings.currencyCode,
                expiresInSec = 12,
            )
            val scored = RuleEngine.score(o, settings.rules())
            activeOffer = scored // so stopWatching()/onInterrupt clear it too
            val delay = if (settings.autoAccept && scored.verdict == Verdict.GOOD)
                settings.autoAcceptDelaySec else 0
            overlay.show(scored, delay) { action ->
                cancelAutoAccept()
                if (action == OverlayController.Action.EXPIRED) offerGone()
                overlay.hide()
            }
            if (delay > 0) scheduleAutoAccept(scored, dead, delay)
            if (settings.voiceAlerts) {
                tts?.speak(
                    "Test offer, ${spokenAmount(o.payoutCents, o.currencySymbol, o.currencyCode)}",
                    TextToSpeech.QUEUE_FLUSH, null, "test",
                )
            }
        }
    }

    /**
     * UI-initiated teardown, for the same frame the switch is flipped: don't
     * wait for the next accessibility event to clear the overlay and any
     * pending auto-accept tap.
     */
    fun stopWatching() = handler.post { endTeach(); offerGone() }

    /**
     * Point-at-the-card teaching: read the app's live tree, then cover the card
     * with a full-screen overlay so each tap maps to the control under it.
     * Unlike the Diagnostics sheet this never works from a stored snapshot.
     */
    fun startTeach(pkg: String) = handler.post {
        if (teachSession != null) return@post
        val nodes = pickWindow(pkg)?.nodes
        if (nodes == null) {
            toast("Open the card first")
            return@post
        }
        if (nodes.isEmpty()) {
            toast("Couldn't read this screen")
            return@post
        }
        // If the card was already dismissed there is nothing to point at, and
        // a mapping saved here would key the wrong layout.
        if (nodes.none { OfferParser.moneyOf(it.text) != null }) {
            toast("No card on screen")
            return@post
        }
        overlay.hide()
        val session = TeachSession(pkg, nodes)
        teachSession = session
        teach = TeachOverlay(
            service = this,
            session = session,
            onSave = { hint -> saveTeach(session, hint) },
            onCancel = { endTeach() },
        ).also { it.show() }
    }

    fun endTeach() {
        teach?.hide()
        teach = null
        teachSession = null
    }

    /**
     * Persist a session's mapping, then prove it against a fresh read: the card
     * may have changed while the driver was pointing at it, and a hint saved
     * against a stale tree would silently miss on the next card.
     */
    private fun saveTeach(session: TeachSession, hint: ParseHints.Hint): String {
        ParseHints.save(this, hint)
        val fresh = pickWindow(hint.pkg)?.nodes ?: session.nodes
        val extra = OfferParser.extraMoney(settings.currencyCode, settings.currencySymbol)
        val offer = OfferParser.parse(
            hint.pkg, fresh, extra, settings.currencySymbol, settings.currencyCode,
            null, hint, settings.distanceUnit,
        )
        endTeach()
        return if (offer == null) "Saved — this screen still doesn't parse"
        else "Saved — reads as " +
            Currencies.format(offer.payoutCents, offer.currencySymbol, offer.currencyCode)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** A window we could read from, plus what it looked like when scored. */
    private data class WindowPick(
        /** The chosen window's tree, flattened once — the parser reads this. */
        val nodes: List<OfferParser.FlatNode>,
        val id: Int,
        val type: Int,
        val pkg: String,
        val candidates: Int,
        val money: Int,
        val cues: Int,
    )

    /**
     * The window of [pkg] that holds the offer, used when teaching or testing a
     * specific app. See [pickBestWindow] for the shared selection logic.
     */
    private fun pickWindow(pkg: String): WindowPick? =
        pickBestWindow(onlyPkg = pkg)

    /**
     * The best visible offer window across every watched app. A driver app can
     * draw its offer over another app (Uber does), so the window event often
     * arrives with the package underneath, not the app that owns the card.
     * Scoring every visible watched window and then taking the topmost winner
     * keeps that card reachable without trusting event.packageName.
     */
    private fun pickBestWindow(onlyPkg: String? = null): WindowPick? {
        val wins = try {
            windows
        } catch (_: Exception) {
            emptyList()
        }
        // The same symbol and ISO code the parser is about to use: a window
        // priced in a currency that only the configured regex knows must score
        // money here, or the window holding the card would look empty.
        val extra = OfferParser.extraMoney(settings.currencyCode, settings.currencySymbol)
        var candidates = 0
        var bestId = -1
        var bestType = -1
        var bestPkg: String? = null
        var bestScore = -1
        var bestMoney = 0
        var bestCues = 0
        var bestLayer = Int.MIN_VALUE
        for (w in wins) {
            val r = w.root ?: continue
            if (!r.isVisibleToUser && !w.isActive && !w.isFocused) {
                r.recycle()
                continue
            }
            val rpkg = r.packageName?.toString()
            if (rpkg == null || rpkg !in watched || (onlyPkg != null && rpkg != onlyPkg)) {
                r.recycle()
                continue
            }
            candidates++
            // Labels only — no bounds, no ids, no FlatNodes. The winner is
            // flattened once below and that tree goes straight to the parser,
            // so a scan walks each window twice at most.
            val s = OfferParser.scoreTree(r, extra, settings.currencySymbol)
            val layer = w.layer
            // Topmost wins a tie; the window list is ordered top to bottom,
            // and our loop keeps the first one when score and layer are equal.
            val better = s.score > bestScore ||
                (s.score == bestScore && layer > bestLayer)
            if (better) {
                bestId = w.id
                bestType = w.type
                bestPkg = rpkg
                bestScore = s.score
                bestMoney = s.money
                bestCues = s.cues
                bestLayer = layer
            }
        }
        if (bestPkg != null) {
            val root = try {
                wins.firstOrNull { it.id == bestId }?.root
            } catch (_: Exception) {
                null
            }
            if (root != null) {
                return WindowPick(
                    OfferParser.flatten(root), bestId, bestType, bestPkg, candidates, bestMoney, bestCues,
                )
            }
        }
        // The window list didn't give us one: fall back to the active root,
        // which is still worth reading even without a window id to report.
        rootInActiveWindow?.let {
            val rpkg = it.packageName?.toString()
            if (rpkg != null && rpkg in watched && (onlyPkg == null || rpkg == onlyPkg)) {
                val nodes = OfferParser.flatten(it)
                val s = OfferParser.scoreScreen(nodes, extra, settings.currencySymbol)
                return WindowPick(nodes, -1, -1, rpkg, candidates, s.money, s.cues)
            }
            it.recycle() // non-matching roots are ours to free
        }
        return null
    }

    /**
     * Re-read the live windows immediately before a tap. A control can vanish
     * while a scan is in flight, so only a fresh tree and fresh bounds may be
     * tapped, never the snapshot that triggered the attempt.
     */
    private fun freshTapTarget(
        pkg: String,
        find: (List<OfferParser.FlatNode>) -> Bounds?,
    ): Bounds? {
        val wins = try {
            windows
        } catch (_: Exception) {
            return null
        }
        for (w in wins) {
            val root = w.root ?: continue
            val name = root.packageName?.toString()
            val visible = root.isVisibleToUser || w.isActive || w.isFocused
            if (name != pkg || !visible) {
                root.recycle()
                continue
            }
            // flatten() recycles the root itself.
            val target = find(OfferParser.flatten(root))
            if (target != null) return target
        }
        return null
    }

    /** Fresh bounds for Uber's Trip Radar pill, or null. */
    private fun freshTripRadarTapTarget(pkg: String): Bounds? =
        freshTapTarget(pkg) { OfferParser.tripRadarTapTarget(it) }

    /** Fresh bounds for a stalled offer list's refresh action, or null. */
    private fun freshStalledListAction(pkg: String): Bounds? =
        freshTapTarget(pkg) { OfferParser.stalledListAction(pkg, it) }

    /**
     * The first visible window owned by a watched app. This keeps the service
     * alive while that app's offer is over another app, without requiring the
     * event itself to carry the watched package.
     */
    private fun visibleWatchedPackage(): String? {
        return try {
            for (w in windows) {
                val r = w.root ?: continue
                if (!r.isVisibleToUser && !w.isActive && !w.isFocused) {
                    r.recycle()
                    continue
                }
                val name = r.packageName?.toString()
                r.recycle()
                if (name != null && name in watched) return name
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * One line describing every window the service can see: its type, layer and
     * the package that owns its root. Diagnostics uses it to tell "the card
     * lives in another window" from "the card is in this window but
     * unreadable".
     */
    private fun windowInventory(): String = try {
        val wins = windows
        if (wins.isEmpty()) "windows=0"
        else "windows=" + wins.joinToString(" ") { w ->
            val r = w.root
            val name = r?.packageName?.toString() ?: "-"
            val visible = r?.isVisibleToUser == true || w.isActive || w.isFocused
            r?.recycle()
            buildString {
                append("#${w.id}:${w.type}:$name/l${w.layer}")
                if (w.isActive) append("/active")
                if (w.isFocused) append("/focused")
                if (!visible) append("/hidden")
            }
        }
    } catch (_: Exception) {
        "windows=?"
    }


    /** Hide the overlay only after the offer's been missing for a bit —
     *  single-frame parse misses are normal (animations, partial draws)
     *  and shouldn't flicker the verdict off. */
    private fun maybeOfferGone(now: Long) {
        if (activeOffer == null) return
        if (now - lastSeenAt < MISS_GRACE_MS) return
        offerGone()
    }

    /**
     * True when one of our own *activity* windows is in front. Our accessibility
     * overlays report the same package name, so a plain package comparison
     * would make every overlay event look like "the user opened Shiftlit".
     */
    private fun ourActivityInFront(): Boolean = try {
        val wins = windows
        if (wins.isEmpty()) true
        else wins.any { w ->
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@any false
            val root = w.root ?: return@any false
            if (!root.isVisibleToUser && !w.isActive && !w.isFocused) {
                root.recycle()
                return@any false
            }
            val ours = root.packageName?.toString() == packageName
            root.recycle()
            ours
        }
    } catch (_: Exception) {
        true
    }

    /** Last-known fix — good enough for tagging where an offer appeared. */
    private fun lastLocation(): Pair<Double, Double>? {
        if (!MileageTracker.hasLocationPermission(this)) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))
                ?.let { it.latitude to it.longitude }
        } catch (_: SecurityException) { null }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventPkg = event.packageName?.toString()
        val now = System.currentTimeMillis()

        // The Monitoring switch is the master gate. Android keeps an enabled
        // service bound, so events keep arriving after it's turned off; every
        // one of them has to be inert — no scan, no shift tracking, no overlay.
        if (!settings.enabled) {
            endTeach()
            offerGone()
            return
        }

        // A teach session owns the screen: ignore the card's own events so the
        // scan cannot redraw over the overlay, and end it only when another app
        // (or a different driver app) takes the screen.
        val teaching = teachSession
        if (teaching != null) {
            if (eventPkg != null && eventPkg != packageName &&
                (eventPkg !in watched || eventPkg != teaching.pkg)) endTeach()
            return
        }

        // Our own overlays carry this package as well. Only our *activity*
        // being in front means the user opened Shiftlit; an overlay's own
        // events must not dismiss the thing that is showing.
        if (eventPkg == packageName && ourActivityInFront()) {
            endTeach()
            // The synthetic test offer is meant to be watched from inside
            // Shiftlit — its own activity must not wipe it.
            val synthetic = activeOffer?.offer?.appPackage == TEST_PKG
            if (!synthetic) offerGone()
            return
        }

        // An Uber offer drawn over Maps can deliver its event with Maps as the
        // package. Look for the watched window itself, not the event sender.
        val visiblePkg = visibleWatchedPackage()
        if (visiblePkg == null) {
            // A real verdict dies with its app. The synthetic test offer is the
            // exception — it is shown while this app is in front precisely so
            // the user can watch it run, so our own events must not wipe it.
            val synthetic = activeOffer?.offer?.appPackage == TEST_PKG
            if (activeOffer != null && !synthetic) offerGone()
            return
        }

        // A driver app is visible — feed the auto-shift tracker even when the
        // event came from the app underneath its overlay.
        onDriverAppSeen(now)

        if (now - lastScanAt < 350) return // throttle contentChanged storms
        lastScanAt = now
        scan(eventPkg ?: visiblePkg, now, attempt = 0, token = ++scanToken)
    }

    /**
     * One read of the driver app's window. [attempt] > 0 is a re-read: offer
     * cards animate in over a few hundred ms, so parsing the first
     * contentChanged event can run before the payout text is laid out.
     */
    private fun scan(fallbackPkg: String?, now: Long, attempt: Int, token: Int) {
        if (token != scanToken) return // superseded by a newer scan

        // Symbols beyond the built-in set (R$, ₩, KSh…) match through the
        // user's configured currency so any market parses.
        val curSymbol = settings.currencySymbol
        val extraRe = OfferParser.extraMoney(settings.currencyCode, curSymbol)

        val pick = pickBestWindow() ?: run {
            // Window exists but its content isn't retrievable (or the window
            // list hasn't caught up yet) — record it so Diagnostics shows
            // "we saw the app but couldn't read it" rather than nothing.
            val diagPkg = fallbackPkg?.takeIf { it in watched } ?: visibleWatchedPackage()
            if (diagPkg != null) {
                Diagnostics.record(
                    this, diagPkg, "idle",
                    listOf("(window not readable)", windowInventory()),
                )
            }
            maybeOfferGone(now)
            return
        }

        val pkg = pick.pkg
        val prefs = settings.rulesFor(pkg) // per-app overrides over globals
        // Monitoring switched off while an overlay was up — tear it down.
        if (!prefs.enabled) { offerGone(); return }

        val nodes = pick.nodes
        val decision = ArrayList<String>(8)
        decision.add("attempt=$attempt nodes=${nodes.size}")
        // Which window produced this read, and why it won. A promo sheet above
        // the card would show up here as the chosen window with no request
        // cues, which is exactly the silent steal this line exists to expose.
        decision.add(
            "window id=${pick.id} type=${pick.type} pkg=${pick.pkg} " +
                "money=${pick.money} cues=${pick.cues}" +
                if (pick.candidates > 1) " (best of ${pick.candidates})" else "",
        )
        // Every read records the whole window stack. A Trip Radar card can be
        // a separate window whose first frame is only a header, and this line
        // is what tells us whether the details are in another window or were
        // never exposed to accessibility at all.
        decision.add(windowInventory())
        // Android 12+ lets an app mark views data-sensitive; a service that
        // isn't an accessibility tool receives them with no text at all. This
        // is the difference between "Uber renders a canvas" and "Uber redacts
        // the card", so it belongs in the recorded tree.
        val dataSensitive = nodes.count { it.dataSensitive }
        decision.add("sensitive=$dataSensitive blank=${nodes.count { it.text.isEmpty() }}")
        val hasMoney = nodes.any {
            OfferParser.moneyOf(it.text, extraRe, curSymbol) != null
        }
        // Spark overlays its offer list with a refresh action — often while
        // orders are still on it — and nothing can be declined until it is
        // tapped. Do this before scoring: acting on the rows behind it is how
        // a decline lands on the wrong order. A decline already in flight
        // owns the screen, and the throttle stops a lingering action from
        // refreshing in a loop.
        if (prefs.autoDecline && pkg == SPARK_PKG && sparkOpenedPayout == 0 &&
            now - lastStalledTapAt > STALLED_REFRESH_MS &&
            OfferParser.stalledListAction(pkg, nodes) != null
        ) {
            val refresh = freshStalledListAction(pkg)
            if (refresh != null) {
                lastStalledTapAt = now
                Diagnostics.record(this, pkg, "idle", nodes, decision)
                Diagnostics.note(this, pkg, "offer list refresh → tapping it (no accept)")
                GesturePerformer.tap(this, refresh) { ok ->
                    if (ok) handler.postDelayed(
                        { scan(pkg, System.currentTimeMillis(), 0, ++scanToken) },
                        800,
                    )
                }
                return
            }
        }

        // A taught mapping for this exact layout, if the user made one. It is
        // only consulted when the heuristics return nothing (see OfferParser).
        val hint = ParseHints.forScreen(this, pkg, nodes)
        if (hint != null) decision.add("taught hint on this screen")
        val offer = OfferParser.parse(
            pkg, nodes, extraRe, curSymbol, settings.currencyCode, decision, hint,
            settings.distanceUnit,
        )
        // Track whether an AI mapping actually works. Throttled: a card can
        // produce several scans, and each write rewrites the hint store.
        val aiPendingMiss = offer == null && hint != null && hint.isAi && hint.pendingCheck
        if (hint != null && hint.isAi && now - hint.lastTriedAt > 30_000L) {
            ParseHints.recordResult(this, pkg, hint.screen, offer != null)
        }
        if (offer == null) {
            // Uber only draws a Trip Radar pill; the actual order is behind
            // a tap. Open the pill so the next read can see and score it.
            // This never accepts or declines, and is controlled by the
            // "Open Trip Radar offers" switch.
            if (settings.autoOpenTripRadar && activeOffer == null &&
                pkg == "com.ubercab.driver"
            ) {
                if (OfferParser.tripRadarTapTarget(nodes) != null &&
                    now - lastRadarTapAt > 5_000L
                ) {
                    val radarTap = freshTripRadarTapTarget(pkg)
                    if (radarTap != null) {
                        lastRadarTapAt = now
                        Diagnostics.record(this, pkg, "idle", nodes, decision)
                        Diagnostics.note(this, pkg, "trip radar pill → opening it (no accept)")
                        GesturePerformer.tap(this, radarTap) { ok ->
                            if (ok) {
                                handler.postDelayed(
                                    { scan(pkg, System.currentTimeMillis(), 0, ++scanToken) },
                                    500,
                                )
                            }
                        }
                        return
                    }
                }
            }
            // A screen with a money figure but no parse is a "miss" — the
            // card was probably there but the layout beat the heuristic.
            // Record it so Diagnostics can show what we saw.
            Diagnostics.record(this, pkg, if (hasMoney) "miss" else "idle",
                nodes, decision)
            maybeLogCompletion(pkg, nodes, now, extraRe, curSymbol)
            // Money, request wording, or a tiny overlay frame means the card
            // may still be animating in. Uber's Trip Radar first appears as a
            // header with no payout, then fills in; re-read through that.
            val retryable = hasMoney || nodes.isEmpty() || pick.cues > 0 || nodes.size <= 8
            if (retryable && attempt < RETRY_DELAYS_MS.size) {
                handler.postDelayed(
                    { scan(pkg, System.currentTimeMillis(), attempt + 1, token) },
                    RETRY_DELAYS_MS[attempt],
                )
                return
            }
            // Surface a failed AI mapping only after the retries, not on a
            // frame captured mid-animation.
            if (aiPendingMiss) {
                Diagnostics.note(
                    this, pkg,
                    "AI mapping did not produce an offer here — remove it in " +
                        "AI setup → Saved mappings if it keeps missing",
                )
                val missKey = pkg + "|" + (hint?.screen ?: "")
                if (lastAiMissKey != missKey) {
                    lastAiMissKey = missKey
                    toast("AI mapping didn't read this screen — undo in AI setup → Saved mappings")
                }
            }
            maybeOfferGone(now)
            return
        }
        Diagnostics.record(this, pkg, "offer", nodes, decision)
        lastAiMissKey = ""

        // Same card still on screen — don't rescore/re-overlay every frame.
        if (offer.fingerprint == lastFingerprint && now - lastSeenAt < 60_000) {
            lastSeenAt = now
            return
        }
        lastFingerprint = offer.fingerprint
        lastSeenAt = now

        val scored = RuleEngine.score(offer, prefs)
        activeOffer = scored
        // The rules belong in the same trail as the parse. An auto-decline that
        // skipped a good offer has to be explainable from the screen it saw,
        // not just from the number it acted on.
        Diagnostics.note(
            this, pkg,
            if (scored.reasons.isEmpty()) "rules all pass"
            else "rules fail: ${scored.reasons.joinToString("; ")}",
        )

        val loc = lastLocation()

        OfferLog.add(this, OfferLog.Entry(
            at = offer.seenAt,
            pkg = pkg,
            payoutCents = offer.payoutCents,
            distanceKm = offer.distanceKm,
            durationMin = offer.durationMin,
            perMileCents = scored.perMileCents,
            perHourCents = scored.perHourCents,
            verdict = scored.verdict.name,
            action = "shown",
            lat = loc?.first,
            lng = loc?.second,
            reservation = offer.isReservation,
            currency = offer.currencySymbol,
            currencyCode = offer.currencyCode,
            rules = scored.reasons.joinToString("; ").ifBlank { null },
            key = offer.fingerprint,
        ))

        // Voice alert for the verdict, when enabled.
        if (settings.voiceAlerts) {
            val label = if (scored.verdict == Verdict.GOOD) "Good offer" else "Skip it"
            tts?.speak(
                "$label, ${spokenAmount(offer.payoutCents, offer.currencySymbol, offer.currencyCode)}",
                TextToSpeech.QUEUE_FLUSH, null, "offer",
            )
        }

        // An AI-picked control is untrusted for automation until the driver has
        // confirmed it on a fresh capture. Manual taps still work; only the
        // automatic countdown and auto-decline are gated.
        val automationTrusted = ParseHints.automationAllowed(hint)
        val autoAcceptDelay =
            if (prefs.autoAccept && scored.verdict == Verdict.GOOD &&
                offer.acceptNode?.clickable == true &&
                automationTrusted &&
                (settings.autoAcceptReservations || !offer.isReservation))
                settings.autoAcceptDelaySec else 0

        // Auto-decline taps before the driver could read a card, so a SKIP IT
        // scorecard would only land over the next screen. GOOD offers always
        // keep their card: auto-accept counts down on it, and with automation
        // off it is the only feedback. Without a tappable decline control the
        // card still shows — nothing else would tell the driver why it failed.
        val autoDeclineNow = prefs.autoDecline && scored.verdict == Verdict.BAD &&
            automationTrusted && offer.declineNode != null

        /** Draw the read-only scorecard and arm its optional timeout. */
        fun showScorecard() {
            overlay.show(scored, autoAcceptDelay) { action ->
                cancelAutoAccept()
                when (action) {
                    OverlayController.Action.ACCEPT -> offer.acceptNode?.let {
                        acceptGesture(it, offer.appPackage)
                        recordAction(scored, "manual_accept")
                    }
                    OverlayController.Action.DECLINE -> offer.declineNode?.let {
                        GesturePerformer.tap(this, it.bounds)
                        recordAction(scored, "manual_decline")
                    }
                    // Offer's on-card timer hit zero — same teardown as the
                    // card vanishing; never leave a pending tap behind.
                    OverlayController.Action.EXPIRED -> offerGone()
                    OverlayController.Action.CANCEL_AUTO -> Unit
                }
                overlay.hide()
            }

            // Optional overlay timeout — 0 = stay until the card disappears,
            // and even then never past MAX_OVERLAY_MS: a verdict for an offer
            // that is long gone is worse than no verdict.
            val secs = prefs.overlaySeconds
            val token = offer.fingerprint
            hideToken = token
            hideRunnable?.let { handler.removeCallbacks(it) }
            val r = Runnable { if (hideToken == token) overlay.hide() }
            hideRunnable = r
            handler.postDelayed(r, if (secs > 0) secs * 1000L else MAX_OVERLAY_MS)
        }

        if (autoDeclineNow) overlay.hide() else showScorecard()

        // Automation: only when the user explicitly enabled it, and only when
        // we actually found the button's bounds. Auto-accept runs on the
        // configured countdown so the driver can cancel.
        if (autoAcceptDelay > 0) {
            scheduleAutoAccept(scored, offer.acceptNode!!, autoAcceptDelay)
        } else if (autoDeclineNow) {
            if (pkg == SPARK_PKG && sparkOpenedPayout != offer.payoutCents) {
                openThenDecline(scored, prefs) { showScorecard() }
            } else {
                offer.declineNode?.let { autoDecline(scored, it) { showScorecard() } }
            }
        }
    }

    /**
     * The accept affordance differs per app: most are a tap on the labelled
     * control, but Amazon Flex uses a "swipe to accept" slider where a tap
     * does nothing. Uber/Lyft need no special gesture — the parser resolves
     * the tappable request card itself for those (tap-anywhere accept).
     */
    private fun acceptGesture(node: NodeRef, pkg: String, done: (Boolean) -> Unit = {}) {
        if (pkg == "com.amazon.rabbit" || node.label.contains("swipe", ignoreCase = true)) {
            GesturePerformer.swipeRight(this, node.bounds, done)
        } else {
            GesturePerformer.tap(this, node.bounds, done)
        }
    }

    /**
     * Tap a decline control, then give the card a moment to go.
     *
     * Some cards carry a second "no" — an explicit Decline beside a close X,
     * or a confirmation after the first tap. Losing the decline to the wrong
     * one leaves the card up with no scorecard (auto-decline suppresses it),
     * so if this same offer is still on screen the live tree is read again
     * and a different control is tapped. Coordinates only ever come from that
     * fresh read: a screen that no longer parses as this offer is left alone.
     */
    private fun autoDecline(
        scored: ScoredOffer,
        node: NodeRef,
        /** Redraws the scorecard when the first tap never dispatched — it was
         *  suppressed in favour of this decline, so the offer would otherwise
         *  sit there with no verdict. A retry keeps whatever is on screen. */
        onFailed: (() -> Unit)? = null,
    ) {
        GesturePerformer.tap(this, node.bounds) { ok ->
            if (!ok) {
                if (activeOffer === scored) onFailed?.invoke()
                return@tap
            }
            recordAction(scored, "auto_decline")
            scheduleDeclineRetry(scored, node.bounds)
        }
    }

    /** At most one retry per offer. */
    private fun scheduleDeclineRetry(scored: ScoredOffer, tried: Bounds) {
        declineRetry?.let { handler.removeCallbacks(it) }
        val key = scored.offer.fingerprint
        if (declineRetryKey != key) {
            declineRetryKey = key
            declineRetried = false
        }
        if (declineRetried) return
        val r = Runnable {
            if (activeOffer !== scored) return@Runnable
            val next = freshDeclineTarget(scored.offer.appPackage, key, tried) ?: return@Runnable
            declineRetried = true
            autoDecline(scored, next)
        }
        declineRetry = r
        handler.postDelayed(r, DECLINE_RETRY_MS)
    }

    private class LiveRead(
        val nodes: List<OfferParser.FlatNode>,
        val offer: DetectedOffer?,
    )

    /** Every visible window of [pkg], flattened and parsed with its mapping. */
    private fun liveReads(pkg: String): List<LiveRead> {
        val wins = try {
            windows
        } catch (_: Exception) {
            return emptyList()
        }
        val curSymbol = settings.currencySymbol
        val extra = OfferParser.extraMoney(settings.currencyCode, curSymbol)
        val out = ArrayList<LiveRead>(2)
        for (w in wins) {
            val root = w.root ?: continue
            val name = root.packageName?.toString()
            val visible = root.isVisibleToUser || w.isActive || w.isFocused
            if (name != pkg || !visible) {
                root.recycle()
                continue
            }
            val nodes = OfferParser.flatten(root) // recycles the root
            out.add(
                LiveRead(
                    nodes,
                    OfferParser.parse(
                        pkg, nodes, extra, curSymbol, settings.currencyCode, null,
                        ParseHints.forScreen(this, pkg, nodes), settings.distanceUnit,
                    ),
                ),
            )
        }
        return out
    }

    /**
     * The next decline control on the live screen, or null when the card is
     * gone, replaced by a different offer, or nothing untried is left.
     */
    private fun freshDeclineTarget(pkg: String, fingerprint: String, tried: Bounds): NodeRef? {
        for (read in liveReads(pkg)) {
            val offer = read.offer
            if (offer != null) {
                if (offer.fingerprint != fingerprint) continue
                val candidates = offer.declineNodes.ifEmpty { listOfNotNull(offer.declineNode) }
                return candidates.firstOrNull { it.bounds != tried }
            }
            // No card parses here: this is the second step of a two-tap
            // decline ("Reject" → "Reject Offer"), which covers the card and
            // shows no distance or duration. Only explicit refusal wording is
            // tapped, never a generic close and never the control already
            // tried.
            return OfferParser.confirmDeclineTarget(read.nodes)?.takeIf { it.bounds != tried }
        }
        return null
    }

    /**
     * Spark broadcasts most orders to every driver at once, and its list
     * reorders as they are claimed: a Reject tapped straight from the list can
     * land on a different order. So open the order first by tapping its payout
     * — the only part of a Spark card it leaves touchable — then reject the
     * order the detail actually shows. [autoDecline]'s retry answers the
     * "Reject Offer" confirmation.
     *
     * The detail is scored on its own because Spark restamps the list from
     * live data: the row that was tapped can open a different amount. A good
     * or unreadable detail gets a fresh scan instead of being left card-less;
     * a bad one is rejected.
     */
    private fun openThenDecline(scored: ScoredOffer, prefs: RulePrefs, onFailed: () -> Unit) {
        val o = scored.offer
        val entry = o.payoutNode
        if (entry == null) {
            Diagnostics.note(
                this, SPARK_PKG,
                "spark open: card has no payout to tap — showing it instead",
            )
            onFailed()
            return
        }
        sparkOpenedPayout = o.payoutCents
        // Spark's list row is not the order: the detail the payout opens is.
        // Every step of that hand-off goes into the trail, because a reject
        // landing on the wrong amount is only explainable from what was
        // tapped and what came back.
        Diagnostics.note(
            this, SPARK_PKG,
            "spark open: tapping payout ${amountOf(o)} at ${boundsOf(entry.bounds)} " +
                "(list row ${distanceOf(o)}, ${minutesOf(o)})",
        )
        GesturePerformer.tap(this, entry.bounds) { ok ->
            if (!ok) {
                sparkOpenedPayout = 0
                Diagnostics.note(this, SPARK_PKG, "spark open: payout tap did not dispatch")
                if (activeOffer === scored) onFailed()
                return@tap
            }
            Diagnostics.note(
                this, SPARK_PKG,
                "spark open: tap dispatched — reading the detail in ${SPARK_OPEN_MS}ms",
            )
            readOpenedDetail(scored, prefs, onFailed, attempt = 0)
        }
    }

    /**
     * Read the detail an opened Spark order shows, then reject it if the rules
     * fail. Spark restamps the list from live data, so the detail — not the row
     * that was tapped — is what gets scored.
     *
     * A detail that hasn't drawn yet is re-read a bounded number of times. If
     * it never parses, the open/refresh guards are released and the card is
     * shown again: leaving them set is how an unreadable order sits on screen
     * while nothing may be tapped.
     */
    private fun readOpenedDetail(
        scored: ScoredOffer,
        prefs: RulePrefs,
        onFailed: () -> Unit,
        attempt: Int,
    ) {
        handler.postDelayed(
            {
                val opened = liveReads(SPARK_PKG).firstNotNullOfOrNull { it.offer }
                if (opened == null) {
                    if (attempt < SPARK_READ_ATTEMPTS) {
                        Diagnostics.note(
                            this, SPARK_PKG,
                            "spark open: detail not readable yet (read ${attempt + 1}) — re-reading",
                        )
                        readOpenedDetail(scored, prefs, onFailed, attempt + 1)
                        return@postDelayed
                    }
                    sparkOpenedPayout = 0
                    Diagnostics.note(
                        this, SPARK_PKG,
                        "spark open: no detail parsed after ${attempt + 1} reads — " +
                            "releasing the open guard and showing the card again",
                    )
                    if (activeOffer === scored) onFailed()
                    return@postDelayed
                }
                sparkOpenedPayout = opened.payoutCents
                val rescored = RuleEngine.score(opened, prefs)
                val node = opened.declineNode
                Diagnostics.note(
                    this, SPARK_PKG,
                    "spark detail: ${amountOf(opened)}, ${distanceOf(opened)}, " +
                        "${minutesOf(opened)} → ${rescored.verdict.name}, " +
                        "reject=${node?.label ?: "none"}",
                )
                if (rescored.verdict == Verdict.BAD && node != null) {
                    Diagnostics.note(
                        this, SPARK_PKG,
                        "spark detail: rejecting it — " + rescored.reasons.joinToString("; "),
                    )
                    autoDecline(rescored, node)
                    return@postDelayed
                }
                Diagnostics.note(
                    this, SPARK_PKG,
                    if (node == null) "spark detail: kept — no reject control on the detail"
                    else "spark detail: kept — rules pass",
                )
                // Good, or nothing to tap: force a fresh score so this detail
                // gets its own card instead of sitting card-less.
                lastFingerprint = ""
                scan(SPARK_PKG, System.currentTimeMillis(), 0, ++scanToken)
            },
            if (attempt == 0) SPARK_OPEN_MS else SPARK_REREAD_MS,
        )
    }

    /** Amount as the card shows it, for a Diagnostics line. */
    private fun amountOf(o: DetectedOffer): String =
        Currencies.format(o.payoutCents, o.currencySymbol, o.currencyCode)

    private fun boundsOf(b: Bounds): String = "${b.left},${b.top},${b.right},${b.bottom}"

    /** Distance in the driver's own unit — Diagnostics must not mix them. */
    private fun distanceOf(o: DetectedOffer): String {
        val km = o.distanceKm ?: return "no distance"
        val isKm = settings.distanceUnit == "KM"
        val v = if (isKm) km else km / 1.609344
        return "%.1f".format(v) + if (isKm) " km" else " mi"
    }

    private fun minutesOf(o: DetectedOffer): String =
        o.durationMin?.let { "%.0f min".format(it) } ?: "no duration"

    /** Tick the overlay countdown down, then tap accept at 0. */
    private fun scheduleAutoAccept(scored: ScoredOffer, node: NodeRef, delaySec: Int) {
        cancelAutoAccept() // a new offer can arrive before the last countdown ended
        var left = delaySec
        val ticker = object : Runnable {
            override fun run() {
                left--
                if (left <= 0) {
                    acceptGesture(node, scored.offer.appPackage) { ok ->
                        if (ok) recordAction(scored, "auto_accept")
                    }
                    overlay.hide()
                } else {
                    overlay.setCountdown(left)
                    handler.postDelayed(this, 1000)
                }
            }
        }
        countdownTicker = ticker
        handler.postDelayed(ticker, 1000)
    }

    /**
     * Auto shift tracking (opt-in): opening any watched driver app starts GPS
     * mileage; 30 minutes without a watched app stops it. The overlay
     * permission we already hold exempts us from background-FGS limits, but
     * guard anyway — worst case is the user taps "Start shift" themselves.
     */
    private fun onDriverAppSeen(now: Long) {
        lastDriverAppAt = now
        if (!settings.autoTrackShift) return
        if (!MileageTracker.hasLocationPermission(this)) return
        if (!MileageTracker.running) {
            try {
                startForegroundService(MileageTracker.startIntent(this))
            } catch (_: Exception) { /* background-start blocked — manual start still works */ }
        }
        // Arm the idle-stop check; every driver-app event pushes it out.
        idleStopCheck?.let { handler.removeCallbacks(it) }
        val check = Runnable {
            if (System.currentTimeMillis() - lastDriverAppAt >= IDLE_STOP_MS &&
                MileageTracker.running
            ) {
                stopService(MileageTracker.stopIntent(this))
            }
        }
        idleStopCheck = check
        handler.postDelayed(check, IDLE_STOP_MS)
    }

    /**
     * Post-trip earnings capture — reads the completed-delivery / earnings
     * summary screen and records the payout as a local earning.
     */
    private fun maybeLogCompletion(
        pkg: String,
        nodes: List<OfferParser.FlatNode>,
        now: Long,
        extra: Regex? = null,
        extraSymbol: String = "$",
    ) {
        if (!settings.autoLogEarnings) return
        val comp = CompletionParser.parse(pkg, nodes, extra, extraSymbol) ?: return
        if (!loggedCompletions.add(comp.fingerprint)) return // already logged this screen
        if (loggedCompletions.size > 200) loggedCompletions.clear()

        val payload = JSONObject()
            .put("amountCents", comp.payoutCents)
            .put("category", "TRIP")
            .put("platformKey", platformKeyFor(pkg))
            .put("notes", "Auto-captured")
            .put("earnedAt", now)
        comp.tipCents?.let { payload.put("tipCents", it) }

        localDb?.insert("earning", payload)
        if (settings.voiceAlerts) {
            tts?.speak(
                // Completion text carries a symbol only; the configured code
                // disambiguates it exactly when the symbol is the configured one.
                "Logged ${spokenAmount(
                    comp.payoutCents, comp.currencySymbol,
                    settings.currencyCode.takeIf { comp.currencySymbol == settings.currencySymbol },
                )}",
                TextToSpeech.QUEUE_ADD, null, "earn",
            )
        }
    }

    /** "12.50 dollars" / "1850 forints" — names and zero-decimal handling
     *  come from the currency registry. */
    private fun spokenAmount(cents: Int, symbol: String, code: String? = null): String =
        Currencies.spokenAmount(cents, symbol, code)

    private fun cancelAutoAccept() {
        countdownTicker?.let { handler.removeCallbacks(it) }
        countdownTicker = null
    }

    private fun recordAction(s: ScoredOffer, action: String) {
        val o = s.offer
        if (o.appPackage == "app.shiftlit.test") return // test offer — don't log
        val failed = s.reasons.joinToString("; ").ifBlank { null }
        // Both halves of the audit: the row carries the failed rules, and the
        // trail says which action they produced. Manual actions are recorded
        // the same way so the two can be compared.
        Diagnostics.note(
            this, o.appPackage,
            if (failed == null) "$action (all rules passed)" else "$action — failed: $failed",
        )
        OfferLog.add(this, OfferLog.Entry(
            at = o.seenAt, pkg = o.appPackage, payoutCents = o.payoutCents,
            distanceKm = o.distanceKm, durationMin = o.durationMin,
            perMileCents = s.perMileCents, perHourCents = s.perHourCents,
            verdict = s.verdict.name, action = action,
            lat = null, lng = null,
            reservation = o.isReservation,
            currency = o.currencySymbol,
            currencyCode = o.currencyCode,
            rules = failed,
            key = o.fingerprint,
        ))
    }

    /** Watched-package → platform key used to tag captured earnings. */
    private fun platformKeyFor(pkg: String): String = when {
        "uber" in pkg -> "uber"
        "lyft" in pkg -> "lyft"
        "doordash" in pkg -> "doordash"
        "instacart" in pkg -> "instacart"
        "amazon" in pkg || "rabbit" in pkg -> "amazon-flex"
        "spark" in pkg || "walmart" in pkg -> "spark"
        "wolt" in pkg -> "wolt"
        "foodora" in pkg || "logistics.rider" in pkg -> "foodora"
        else -> "other"
    }

    private fun offerGone() {
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideToken = ""
        declineRetry?.let { handler.removeCallbacks(it) }
        declineRetry = null
        declineRetryKey = ""
        declineRetried = false
        sparkOpenedPayout = 0
        cancelAutoAccept() // never tap bounds for a card that's no longer up
        activeOffer = null
        lastFingerprint = ""
        overlay.hide()
    }

    override fun onInterrupt() {
        endTeach()
        offerGone()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        // Service toggled off in Settings — tear everything down now, don't
        // wait for onDestroy or a stale `instance` would still answer the UI.
        endTeach()
        offerGone()
        stopWindowPoll()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        stopWindowPoll()
        cancelAutoAccept()
        hideRunnable?.let { handler.removeCallbacks(it) }
        idleStopCheck?.let { handler.removeCallbacks(it) }
        overlay.hide()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
