package app.shiftlit.driver

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Heuristic offer-card extractor.
 *
 * Driver apps change their layouts constantly and don't publish view-tree
 * contracts, so this intentionally works off *textual patterns* rather than
 * view IDs: find currency figures, distance and duration text, and the
 * accept/decline affordances. Tuned per-app where a reliable anchor exists.
 */
object OfferParser {

    // A parse that survives the heuristics can still be nonsense — a customer
    // total read as the payout, a bonus banner read as a rate. Readings outside
    // these ranges are treated as "couldn't read" rather than shown as a
    // confident, wrong verdict. Wide on purpose: real offers land well inside.
    //
    // The bounds are USD per km / USD per hour: the rate is normalized through
    // Currencies.usdScale first, or a 1 850 Ft card would read as 154/km and be
    // thrown away for being in a currency the numbers weren't written for.
    private val PLAUSIBLE_PER_KM = 0.15..25.0    // ≈ $0.24–40 per mile
    private val PLAUSIBLE_PER_HOUR = 1.5..250.0

    // Money: symbol/code-prefixed ($12.50, €8.90) or code-suffixed (850 Ft,
    // 12,50 EUR). Drivers in Europe get Ft/kr/zł cards — $-only parsing
    // means nothing is ever detected. Deliberately case-sensitive: lowercase
    // "ft" is feet, not forint. The (?![\p{L}\p{N}]) tail instead of \b keeps
    // tokens ending in ł/č/₺ matching — and stays inside what Android's
    // ICU regex engine can compile (no (?U), no lookbehind).
    val MONEY = Regex(
        "([$€£₺₴]|USD|EUR|GBP)\\s*(\\d(?:[\\d., '\\u00A0]{0,12}\\d)?)|" +
            "(\\d(?:[\\d., '\\u00A0]{0,12}\\d)?)\\s*" +
            "(Ft|HUF|huf|EUR|eur|kr|Kr|KR|SEK|NOK|DKK|PLN|pln|zł|Zł|RON|ron|" +
            "lei|CZK|czk|Kč|TL|TRY|uah|UAH|₺|₴|USD|usd|\\$|€|£)(?![\\p{L}\\p{N}])",
    )
    private val CURRENCY_WORDS = mapOf(
        "$" to "$", "€" to "€", "£" to "£",
        "ft" to "Ft", "huf" to "Ft", "eur" to "€", "gbp" to "£",
        "kr" to "kr", "sek" to "kr", "nok" to "kr", "dkk" to "kr",
        "pln" to "zł", "zł" to "zł", "ron" to "lei", "lei" to "lei",
        "czk" to "Kč", "kč" to "Kč", "tl" to "₺", "try" to "₺", "₺" to "₺",
        "uah" to "₴", "₴" to "₴", "usd" to "$",
    )

    /**
     * Locale-safe amount parse: "12,50"→12.5, "1.850"→1850 (3+ digits after
     * the last separator = thousands), "2 340"→2340, "1,234.56"→1234.56.
     */
    private fun parseAmount(raw: String): Double? {
        val s = raw.replace(" ", "").replace(" ", "").replace("'", "")
        val lastSep = maxOf(s.lastIndexOf('.'), s.lastIndexOf(','))
        val normalized = if (lastSep >= 0 && s.length - lastSep - 1 in 1..2) {
            s.substring(0, lastSep).filter { it.isDigit() } + "." + s.substring(lastSep + 1)
        } else {
            s.filter { it.isDigit() }
        }
        return normalized.toDoubleOrNull()
    }

    /**
     * Money matcher for the user's *configured* currency, covering symbols
     * the built-in set doesn't know (R$, ₩, "KSh", …). Both the symbol and
     * the ISO code match, prefix or suffix. Cached — building a regex on
     * every 350ms scan would be wasteful.
     */
    private var extraKey = ""
    private var extraRe: Regex? = null

    /** ISO codes that double as everyday words — "TOP 100 drivers" must not
     *  parse as 100 pa'anga. The symbol still matches for these currencies. */
    private val WORDY_CODES = setOf(
        "ALL", "AND", "ARE", "BAM", "BOB", "BUT", "CAN", "FOR", "GEL", "GET",
        "HAS", "HIT", "HOT", "MAD", "MAN", "NEW", "NOW", "OFF", "ONE", "PAY",
        "PEN", "PER", "RON", "SAR", "SEE", "SOS", "TOP", "TRY", "TWO", "USE",
        "VES", "WON", "YER", "CUSTOM",
    )

    fun extraMoney(code: String, symbol: String): Regex? {
        val toks = listOf(symbol, code)
            .map { it.trim() }
            .filter {
                it.isNotEmpty() &&
                    it.lowercase() !in CURRENCY_WORDS &&
                    it.uppercase() !in WORDY_CODES
            }
            .distinct()
        if (toks.isEmpty()) return null
        val key = toks.joinToString("|")
        if (key == extraKey) return extraRe
        val alt = toks.joinToString("|") { Regex.escape(it) }
        val num = "(\\d(?:[\\d., '\\u00A0]{0,12}\\d)?)"
        // Lookahead, not \b — \b can't anchor after non-word symbols (₺, ₩, R$)
        // so "100 ₺" would silently fail at end-of-string.
        extraRe = Regex("(?:$alt)\\s*$num|$num\\s*(?:$alt)(?![\\p{L}\\p{N}])")
        extraKey = key
        return extraRe
    }

    /** Extract amount + display symbol from a text, or null. [extra] is the
     *  configured-currency matcher from [extraMoney]; its hits report
     *  [extraSymbol] as the currency. */
    fun moneyOf(text: String, extra: Regex? = null, extraSymbol: String = "$"): Pair<Double, String>? {
        MONEY.find(text)?.let { m ->
            val amount = parseAmount(m.groupValues[2].ifEmpty { m.groupValues[3] })
                ?: return@let
            val token = m.groupValues[1].ifEmpty { m.groupValues[4] }
            return amount to (CURRENCY_WORDS[token.lowercase()] ?: "$")
        }
        val m = extra?.find(text) ?: return null
        val amount = parseAmount(m.groupValues[1].ifEmpty { m.groupValues[2] })
            ?: return null
        return amount to extraSymbol
    }
    // Comma decimals ("12,4 km") are how most of Europe writes them.
    private val MILES = Regex("""(\d{1,3}(?:[.,]\d+)?)\s*(mi|miles?|mile)\b""", RegexOption.IGNORE_CASE)
    private val KM = Regex("""(\d{1,3}(?:[.,]\d+)?)\s*km\b""", RegexOption.IGNORE_CASE)
    // "min" covers most EU languages; Hungarian offers show "perc" instead.
    private val MINUTES = Regex(
        """(\d{1,3})\s*(minutes|minuten|minutter|minuter|minute|mins?|perc|percet|minut)\b""",
        RegexOption.IGNORE_CASE,
    )
    // "1 hr 20 min", "2 h", "1 óra 30 perc", "2 godz.", "3 timer"… The
    // comma before the minutes is part of the label on Uber's long trips
    // ("2 hrs, 35 min"); without it the match stopped at the hours and the
    // 155-minute job was scored as 120.
    private val HOURS_MIN = Regex(
        """(\d{1,2})\s*(h|hr|hrs|hour|hours|óra|godz|hod|tuntia|tim|timer)\b\.?\s*,?\s*(\d{1,2})?\s*(min|perc|minut)?""",
        RegexOption.IGNORE_CASE,
    )
    // "tip" spans most markets; add the ones that differ (HU/DE/PL/FR/ES/IT/…).
    private val TIP_WORD = Regex(
        """tip|gratuit|borraval|trinkgeld|napiw|pourboire|propina|mancia|dricks|drikkepenge|juomarah|spropitn|bahş|чаєв""",
        RegexOption.IGNORE_CASE,
    )

    // Accept wording across the markets Wolt/foodora actually localize to.
    // Word stems keep the table compact (Hungarian "Elfogadás/Elfogadom",
    // Polish "Akceptuj/Przyjmij"…). "swipe" catches Flex's slider label.
    private val ACCEPT_WORDS = Regex(
        """\bmatch\b|accept|confirm|match me|claim|grab it|start delivery|swipe|""" +
            """accepter|aceptar|aceitar|acceptă|akzept|aksep|annehm|akcept|przyjm|přijm|prija|""" +
            """elfogad|átvett|átvesz|hyväks|godkänn|godkjenn|godta|kabul|zaprim|prejmi|""" +
            """прийма|прийнят|приеми|acceptez|pastāst|patvirt""",
        RegexOption.IGNORE_CASE,
    )
    // "Schedule"/"Book" = claiming a *reserved* block (Amazon Flex) — only
    // applied on screens we already flagged as reservations, so the Schedule
    // nav tab can't be mistaken for an accept affordance.
    private val RESERVE_ACCEPT = Regex("""schedule|book|reserve|foglal|boka""", RegexOption.IGNORE_CASE)
    private val DECLINE_WORDS = Regex(
        """decline|\bpass\b|reject|no thanks|skip|close|dismiss|refus|rechaz|recus|ablehn|avvis|afvis|""" +
            """odmítn|odmiet|odrzu|hylk|elutasít|visszautasít|reddet|atmest|noraid|""" +
            """відхил|отказ|norite|refuza""",
        RegexOption.IGNORE_CASE,
    )
    /** The same wording minus close/dismiss: a control that names the refusal
     *  itself. Ranking only — a card whose only no is labelled "Close" still
     *  gets it (see [declineCandidates]). */
    private val PRIMARY_DECLINE = Regex(
        """decline|\bpass\b|reject|no thanks|skip|refus|rechaz|recus|ablehn|avvis|afvis|""" +
            """odmítn|odmiet|odrzu|hylk|elutasít|visszautasít|reddet|atmest|noraid|""" +
            """відхил|отказ|norite|refuza""",
        RegexOption.IGNORE_CASE,
    )
    private val TIMER_HINTS = Regex("""^\d{1,2}$|seconds|másodperc|sekund""", RegexOption.IGNORE_CASE)
    // Offer expiry countdown: "0:12", "12s", "12 sec", "expires in 12".
    private val EXPIRY = Regex(
        """^(\d{1,2}):(\d{2})$|^(\d{1,2})\s*(s|sec|seconds?|mp)\b|expires? in (\d{1,2})""",
        RegexOption.IGNORE_CASE,
    )
    private val RESERVATION = Regex(
        """reserv|scheduled|foglal|ütemez|rezerv|planowan|zaplanowan|bokad|planlagt|varattu|забронь|зарезерв""",
        RegexOption.IGNORE_CASE,
    )
    /** A card that is already gone: its Match/Accept-looking label is not a
     *  live control, so the overlay must not offer it as one. */
    private val UNAVAILABLE = Regex(
        """another driver matched|no longer available|already matched|already taken|""" +
            """request (?:is )?(?:unavailable|expired)|offer (?:is )?(?:expired|no longer available)""",
        RegexOption.IGNORE_CASE,
    )
    /** The single acknowledgement on a dead offer's dialog. */
    private val UNAVAILABLE_DISMISS = Regex(
        """^(got it|ok|okay|dismiss|close|thanks?)$""",
        RegexOption.IGNORE_CASE,
    )

    // A day's-work summary — earnings, wallet, balance — carries money and a
    // length of time, and used to score as an offer: "new offer $94" for the
    // total made today. These words don't appear on a live request card.
    private val SUMMARY_WORDS = Regex(
        """earned|earnings|you made|you've made|balance|wallet|this week|this month|""" +
            """weekly|payout history|cash out|withdraw|online time|hours online|time online|""" +
            """total today|today's total|completed trips|kereset|bevétel|einnahmen|guthaben|zarobk""",
        RegexOption.IGNORE_CASE,
    )

    /** Wording that makes a screen a request rather than a money summary. */
    private val OFFER_WORDS = Regex(
        """new (offer|order|ride|request|job|delivery|trip)|incoming (offer|order|ride|request)|""" +
            """ride request|order request|delivery request|trip radar|fuvar|rendelés|auftrag|solicitud|заказ""",
        RegexOption.IGNORE_CASE,
    )

    /** Apps where the whole request card accepts on tap — no labelled button.
     *  Uber docs: "tap anywhere on the black bar"; Lyft: "tap anywhere". */
    private val TAP_ANYWHERE_PKGS = setOf("com.ubercab.driver", "com.lyft.android.driver")

    private const val SPARK_PKG = "com.walmart.sparkdriver"

    data class FlatNode(
        val text: String,
        val viewId: String?,
        val bounds: Bounds,
        val clickable: Boolean,
        /** Android 14+ redaction: the view declares its content sensitive, and
         *  a service that isn't an accessibility tool receives it with no
         *  text. Recorded so a blank snapshot explains itself. */
        val dataSensitive: Boolean = false,
    )

    fun flatten(root: AccessibilityNodeInfo): List<FlatNode> {
        val out = ArrayList<FlatNode>(64)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            val t = n.text?.toString()?.trim().orEmpty()
            val cd = n.contentDescription?.toString()?.trim().orEmpty()
            val label = if (t.isNotEmpty()) t else cd
            // Copy everything before recycle() — FlatNode keeps no node refs.
            val r = Rect()
            n.getBoundsInScreen(r)
            val viewId = n.viewIdResourceName
            val clickable = n.isClickable
            // The flag exists earlier but is only enforced from Android 14;
            // below that it would be a method that isn't there.
            val dataSensitive =
                android.os.Build.VERSION.SDK_INT >= 34 && n.isAccessibilityDataSensitive
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let(stack::add)
            }
            n.recycle()
            // Unlabeled clickable nodes are kept: an offer's close "×" often has
            // no text, no id and no description, and teaching it needs it listed.
            if (label.isNotEmpty() || clickable) {
                out.add(
                    FlatNode(
                        label, viewId, Bounds(r.left, r.top, r.right, r.bottom), clickable, dataSensitive,
                    ),
                )
            }
        }
        return out
    }

    /**
     * Uber's Trip Radar pill is the only part of a radar offer on screen
     * until it is opened. The title itself is not clickable; return the
     * smallest clickable ancestor around it so a tap opens the list. Null
     * means this screen has no radar pill.
     */
    fun tripRadarTapTarget(nodes: List<FlatNode>): Bounds? {
        val title = nodes.firstOrNull {
            it.viewId?.endsWith("ub__driver_job_offers_pill_title") == true ||
                it.text.equals("Trip Radar", ignoreCase = true)
        } ?: return null
        val cx = title.bounds.centerX()
        val cy = title.bounds.centerY()
        val parent = nodes.asSequence()
            .filter { it.clickable && it.bounds.contains(cx, cy) }
            .minByOrNull { it.bounds.width().toLong() * it.bounds.height().toLong() }
        // A full-screen clickable root is not the pill; tapping the title's
        // center still lands inside the real pill in that case.
        return if (parent != null && parent.bounds.width() < 900) parent.bounds else title.bounds
    }

    /**
     * How much a window looks like a live offer card. Money is the floor — a
     * window with no amount can't produce a verdict — and request wording is
     * what separates a card from a wallet or an earnings summary.
     *
     * [score] deliberately ignores how much money there is: a wallet with five
     * amounts must not outrank the one card sitting above it. Equal scores are
     * left to the caller, which keeps window order (topmost first) as the
     * tiebreak.
     */
    data class ScreenScore(val money: Int, val cues: Int) {
        val score: Int get() = if (money == 0) 0 else 10 + cues * 5
    }

    fun scoreScreen(
        nodes: List<FlatNode>,
        extra: Regex? = null,
        extraSymbol: String = "$",
    ): ScreenScore {
        var money = 0
        var cues = 0
        for (n in nodes) {
            val s = countLabel(n.text, extra, extraSymbol)
            money += s.money
            cues += s.cues
        }
        return ScreenScore(money, cues)
    }

    /**
     * One label, counted once. Both scorers go through this, so the cheap tree
     * walk and the FlatNode scorer can't drift apart on what counts as money or
     * as a request cue.
     */
    private fun countLabel(label: String, extra: Regex?, extraSymbol: String): ScreenScore {
        if (label.isEmpty()) return ScreenScore(0, 0)
        val money = if (moneyOf(label, extra, extraSymbol) != null) 1 else 0
        val cues = if (OFFER_WORDS.containsMatchIn(label) ||
            ACCEPT_WORDS.containsMatchIn(label) ||
            DECLINE_WORDS.containsMatchIn(label) ||
            RESERVE_ACCEPT.containsMatchIn(label) ||
            EXPIRY.containsMatchIn(label)
        ) 1 else 0
        return ScreenScore(money, cues)
    }

    /**
     * Cheap score of a whole window: labels only — no bounds, no view ids, no
     * [FlatNode] list. Choosing between windows needs the score, not the tree,
     * and the winner is flattened exactly once afterwards. The root is recycled
     * the way [flatten] does it.
     */
    fun scoreTree(
        root: AccessibilityNodeInfo,
        extra: Regex? = null,
        extraSymbol: String = "$",
        limit: Int = 400,
    ): ScreenScore {
        var money = 0
        var cues = 0
        var seen = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            if (seen++ >= limit) {
                n.recycle()
                continue
            }
            val text = n.text?.toString()?.trim().orEmpty()
            val label = if (text.isNotEmpty()) text
            else n.contentDescription?.toString()?.trim().orEmpty()
            val s = countLabel(label, extra, extraSymbol)
            money += s.money
            cues += s.cues
            for (i in 0 until n.childCount) n.getChild(i)?.let(stack::add)
            n.recycle()
        }
        return ScreenScore(money, cues)
    }

    /**
     * Whether a screen that didn't parse still looks like a single offer worth
     * teaching on: request wording, a decision label, or a countdown. A wallet
     * or earnings screen has money but none of those, and a teach bar there is
     * pure noise.
     */
    fun looksLikeOfferForTeaching(
        nodes: List<FlatNode>,
        extra: Regex? = null,
        extraSymbol: String = "$",
    ): Boolean {
        if (looksLikeSummary(nodes, extra, extraSymbol)) return false
        return nodes.any { n ->
            OFFER_WORDS.containsMatchIn(n.text) ||
                ACCEPT_WORDS.containsMatchIn(n.text) ||
                DECLINE_WORDS.containsMatchIn(n.text) ||
                RESERVE_ACCEPT.containsMatchIn(n.text) ||
                EXPIRY.containsMatchIn(n.text)
        }
    }

    /**
     * A day's work, not one trip: summary wording, or several distances or pay
     * figures. An offer card carries one of each — a trip list carries many.
     */
    private fun looksLikeSummary(
        nodes: List<FlatNode>,
        extra: Regex?,
        extraSymbol: String,
    ): Boolean {
        // A live card outranks the summary heuristic. An order countdown or an
        // explicit reject control means this is an offer (or the offer list
        // holding one), even when the app's nav names "Earnings" or a sheet of
        // orders carries several distances. Earnings and wallet screens carry
        // neither, so they stay guarded.
        if (nodes.any { EXPIRY.containsMatchIn(it.text.trim()) } ||
            nodes.any { PRIMARY_DECLINE.containsMatchIn(it.text) }
        ) return false
        var distanceish = 0
        var money = 0
        for (n in nodes) {
            val t = n.text
            if (SUMMARY_WORDS.containsMatchIn(t)) return true
            if (MILES.containsMatchIn(t) || KM.containsMatchIn(t) ||
                MINUTES.containsMatchIn(t) || HOURS_MIN.containsMatchIn(t)
            ) {
                distanceish++
            }
            if (moneyOf(t, extra, extraSymbol) != null) money++
        }
        return distanceish >= 4 || money >= 5
    }

    /**
     * Every control that can say "no" to this card, best first.
     *
     * A card often carries more than one: an explicit Decline beside a close
     * X, a "Dismiss" on a banner behind the card, or a navigation "Close" at
     * the top of the screen. Taking the first match in tree order tapped
     * whichever the layout happened to declare first — on a real Uber card a
     * navigation close can come before the card's own X, and that tap only
     * closes the map. So an explicit decline verb outranks a generic
     * close/dismiss, a clickable control outranks a bare label, and proximity
     * to the payout breaks the rest.
     */
    private fun declineCandidates(
        nodes: List<FlatNode>,
        pkg: String,
        payout: FlatNode?,
        available: Boolean,
    ): List<NodeRef> {
        class Pick(val node: NodeRef, val primary: Boolean, val distance: Long)

        val picks = ArrayList<Pick>(4)
        val seen = HashSet<String>()
        fun consider(ref: NodeRef, primary: Boolean? = null) {
            val b = ref.bounds
            if (b.width() <= 0 || b.height() <= 0) return
            if (!seen.add("${b.left},${b.top},${b.right},${b.bottom}")) return
            val dx = (b.centerX() - (payout?.bounds?.centerX() ?: b.centerX())).toLong()
            val dy = (b.centerY() - (payout?.bounds?.centerY() ?: b.centerY())).toLong()
            picks.add(
                Pick(ref, primary ?: PRIMARY_DECLINE.containsMatchIn(ref.label), dx * dx + dy * dy),
            )
        }

        nodes.filter { it.text.isNotBlank() && DECLINE_WORDS.containsMatchIn(it.text) }
            .forEach { consider(NodeRef(it.bounds, it.text, it.clickable)) }
        unnamedUberDecline(nodes, pkg, payout, available)?.let { consider(it, primary = true) }

        return picks.sortedWith(
            compareByDescending<Pick> { it.primary }
                .thenByDescending { it.node.clickable }
                .thenBy { it.distance },
        ).map { it.node }
    }

    /**
     * Uber's decline control is an unlabeled close icon in the card's
     * top-right. It has no text or view id to match, so find it by shape and
     * position relative to the payout. Big clickable nodes (the card, the
     * root) and map markers elsewhere on screen are excluded.
     */
    private fun unnamedUberDecline(
        nodes: List<FlatNode>,
        pkg: String,
        payout: FlatNode?,
        available: Boolean,
    ): NodeRef? {
        if (pkg != "com.ubercab.driver" || !available) return null
        val m = payout ?: return null
        return nodes
            .filter { n ->
                n.clickable &&
                    n.text.isBlank() &&
                    n.viewId.isNullOrBlank() &&
                    n.bounds.width() in 48..260 &&
                    n.bounds.height() in 48..260 &&
                    n.bounds.centerX() > m.bounds.right &&
                    n.bounds.centerY() in (m.bounds.top - 360)..(m.bounds.bottom + 240)
            }
            .minByOrNull { Math.abs(it.bounds.centerY() - m.bounds.centerY()) }
            ?.let { NodeRef(it.bounds, "decline (close X)", true) }
    }

    /**
     * The reject/decline control on a screen with no offer card of its own.
     *
     * Some apps decline in two taps — Spark shows "Reject" on the card, then
     * a confirm sheet whose own button says "Reject Offer". That sheet
     * covers the card and carries no distance or duration, so it never
     * parses as an offer. Only explicit refusal wording counts here: a
     * generic Close could dismiss the sheet without declining, and closing
     * is what the driver must never have chosen for them.
     */
    fun confirmDeclineTarget(nodes: List<FlatNode>): NodeRef? =
        confirmDeclineTargets(nodes).firstOrNull()

    /**
     * Every explicit refusal on a screen that carries a confirm sheet. The
     * card behind the sheet can still be in the same tree, so its Reject is in
     * this list too — the caller picks the first one it hasn't already tried.
     */
    fun confirmDeclineTargets(nodes: List<FlatNode>): List<NodeRef> =
        nodes
            .filter { it.text.isNotBlank() && PRIMARY_DECLINE.containsMatchIn(it.text) }
            .sortedWith(
                compareByDescending<FlatNode> { it.clickable }
                    .thenByDescending { it.bounds.width().toLong() * it.bounds.height().toLong() },
            )
            .map { NodeRef(it.bounds, it.text, it.clickable) }

    /**
     * The refresh action Spark puts over its offer list.
     *
     * It shows with orders still on screen, not just on an empty list, and
     * nothing can be declined until it is tapped. Its label never appears in
     * the tree — the action is blank, and the snackbar says only "You have
     * new offer(s) available." or "Something went wrong. Please try again."
     * The view id `com.walmart.sparkdriver:id/snackbar_action` is unique to
     * this control (nothing else in the app has used it in a year of use), so
     * the id is the match. Tapping it only reloads the list; it never accepts
     * or declines.
     */
    /**
     * The acknowledgement on Spark's "Offer unavailable" dialog.
     *
     * Spark leaves that dialog over the offer list when an order expires or
     * another driver takes it: "This offer is no longer available because it
     * either expired, got accepted by another driver, or the order(s)
     * changed." It blocks the list and every control behind it until it is
     * dismissed, so nothing else can be seen or declined. Dismissing it is
     * not a refusal — the order is already gone — and the only control it
     * offers is that acknowledgement.
     */
    fun unavailableDismissTarget(pkg: String, nodes: List<FlatNode>): Bounds? {
        if (pkg != SPARK_PKG) return null
        val dead = nodes.any {
            it.text.contains("offer unavailable", ignoreCase = true) ||
                UNAVAILABLE.containsMatchIn(it.text)
        }
        if (!dead) return null
        // The label's own bounds are inside the button, so tapping them works
        // whether or not the button node itself carries the text.
        return nodes
            .firstOrNull {
                UNAVAILABLE_DISMISS.matches(it.text.trim()) &&
                    it.bounds.width() > 0 && it.bounds.height() > 0
            }?.bounds
    }

    fun stalledListAction(pkg: String, nodes: List<FlatNode>): Bounds? {
        if (pkg != SPARK_PKG) return null
        return nodes.firstOrNull { n ->
            n.viewId?.endsWith("id/snackbar_action") == true && n.bounds.width() > 0
        }?.bounds
    }

    fun parse(
        pkg: String,
        nodes: List<FlatNode>,
        extra: Regex? = null,
        extraSymbol: String = "$",
        /** ISO code of the configured currency — disambiguates shared symbols
         *  such as "¥" (JPY and CNY) and "kr" (SEK/NOK/DKK). */
        currencyCode: String? = null,
        /** Optional decision trail, one line per step — surfaced in Diagnostics. */
        trace: MutableList<String>? = null,
        /** User-taught node mapping for a screen the heuristics can't read. */
        hint: ParseHints.Hint? = null,
        /** Display unit for a bare taught distance: "MI" (default) or "KM". */
        unit: String = "MI",
    ): DetectedOffer? {
        // Hints only ever apply where the heuristics fail. A screen that already
        // parses is never re-read through a taught mapping, so a wrong hint can
        // neither change nor break a result that works today.
        val auto = parseOnce(pkg, nodes, extra, extraSymbol, currencyCode, trace, null, unit)
        if (auto != null) {
            // The amounts are settled: the heuristics own them, and no taught
            // mapping may change a price. Taught *buttons* still apply — a card
            // can read perfectly while its accept/decline control is an
            // unlabeled icon the label search never finds.
            val accept = hint?.accept?.let { ParseHints.resolve(nodes, it) }?.ref()
            val decline = hint?.decline?.let { ParseHints.resolve(nodes, it) }?.ref()
            if (accept == null && decline == null) {
                if (hint != null) trace?.add("hint: ignored (screen already parses)")
                return auto
            }
            trace?.add(
                "hint: taught buttons accept=${accept?.label ?: "none"} " +
                    "decline=${decline?.label ?: "none"}",
            )
            return auto.copy(
                acceptNode = accept ?: auto.acceptNode,
                declineNode = decline ?: auto.declineNode,
            )
        }
        if (hint == null) return null
        trace?.add("hint: heuristics missed, trying taught nodes")
        val legacyRoles = hint?.let { h ->
            listOfNotNull(
                "payout".takeIf { ParseHints.isLegacy(h.payout) },
                "distance".takeIf { ParseHints.isLegacy(h.distance) },
                "duration".takeIf { ParseHints.isLegacy(h.duration) },
                "accept".takeIf { ParseHints.isLegacy(h.accept) },
                "decline".takeIf { ParseHints.isLegacy(h.decline) },
            )
        }.orEmpty()
        if (legacyRoles.isNotEmpty()) {
            trace?.add("hint: saved mapping is out of date (${legacyRoles.joinToString("/")})")
        }
        return parseOnce(pkg, nodes, extra, extraSymbol, currencyCode, trace, hint, unit)
    }

    private fun parseOnce(
        pkg: String,
        nodes: List<FlatNode>,
        extra: Regex?,
        extraSymbol: String,
        currencyCode: String?,
        trace: MutableList<String>?,
        hint: ParseHints.Hint?,
        unit: String,
    ): DetectedOffer? {
        val texts = nodes.map { it.text }
        val unavailable = texts.any { UNAVAILABLE.containsMatchIn(it) }

        // Collect money figures with their context. The card total is usually
        // the largest standalone amount; "incl. tip" lines become tips.
        data class Money(val node: FlatNode, val amount: Double, val currency: String, val raw: String)
        val moneyNodes = nodes.mapNotNull { n ->
            moneyOf(n.text, extra, extraSymbol)?.let { (amount, cur) -> Money(n, amount, cur, n.text) }
        }
        if (moneyNodes.isEmpty() && hint?.payout == null) {
            trace?.add("money=0 → not an offer")
            return null
        }

        // Money on a wallet or an earnings screen is not an offer, and not a
        // miss worth teaching either. A mapping the user made is still obeyed
        // — that is an explicit statement that the screen is a card.
        if (hint == null && looksLikeSummary(nodes, extra, extraSymbol)) {
            trace?.add("summary screen (earnings/wallet) → not an offer")
            return null
        }

        // A taught payout wins over the heuristics; it is the only reason this
        // branch exists, and it is parsed as leniently as the node allows.
        val taughtPayout = hint?.payout?.let { a -> ParseHints.resolve(nodes, a) }
        val taughtMoney = taughtPayout?.let { n ->
            moneyOf(n.text, extra, extraSymbol) ?: lenientMoney(n.text, extraSymbol)
        }
        // A taught payout that can't be read means the hint is stale (the app
        // renamed the node). Don't guess with the next money figure on screen.
        val hintPayout = hint?.payout
        if (hintPayout != null && taughtMoney == null) {
            trace?.add(
                when {
                    ParseHints.isLegacy(hintPayout) ->
                        "hint: out-of-date anchor $hintPayout → not an offer"
                    hintPayout != null && taughtPayout == null ->
                        "hint: its payout node is gone ($hintPayout) → not an offer"
                    else -> "hint: taught payout unreadable → not an offer"
                },
            )
            return null
        }
        val payout = taughtMoney?.first?.takeIf { it > 0 }
            ?: moneyNodes
                .filter { it.amount > 0.5 && !TIP_WORD.containsMatchIn(it.raw) }
                .maxOfOrNull { it.amount } ?: return null
        val payoutMoney = moneyNodes.firstOrNull { it.amount == payout }
        val currency = taughtMoney?.second ?: (payoutMoney?.currency ?: "$")
        trace?.add(
            when {
                taughtMoney != null && taughtPayout != null ->
                    "taught payout=$payout from ${taughtPayout.text}"
                else -> "money=${moneyNodes.size} payout=$payout from ${payoutMoney?.raw ?: "?"}"
            },
        )

        val tip = moneyNodes
            .filter { TIP_WORD.containsMatchIn(it.raw) }
            .maxOfOrNull { it.amount }

        // Distances: take the largest plausible one (total trip distance).
        val distanceKm = hint?.distance?.let { a -> ParseHints.resolve(nodes, a) }
            ?.let { lenientDistance(it.text, unit) }?.takeIf { it in 0.2..500.0 }
            ?: texts.mapNotNull { distanceOf(it) }
                .filter { it in 0.2..500.0 }.maxOrNull()

        // Duration: ignore countdown timers (tiny ints, "seconds").
        val durationMin = hint?.duration?.let { a -> ParseHints.resolve(nodes, a) }
            ?.let { lenientDuration(it.text) }?.takeIf { it in 1.0..600.0 }
            ?: texts.mapNotNull { durationOf(it) }
                .filter { it in 1.0..600.0 }.maxOrNull()

        // Expiry countdown — "0:15" / "12s" style, the platform ticks it down.
        val expiresInSec = texts.mapNotNull { t ->
            EXPIRY.find(t.trim())?.let { m ->
                val mm = m.groupValues[1].toIntOrNull()
                if (mm != null) mm * 60 + (m.groupValues[2].toIntOrNull() ?: 0)
                else (m.groupValues[3].ifEmpty { m.groupValues[5] }).toIntOrNull()
            }
        }.filter { it in 1..300 }.minOrNull() // Instacart batch timers run ~4min

        trace?.add("distance=$distanceKm duration=$durationMin")

        // Need at least payout + one of distance/duration to call it an offer.
        if (distanceKm == null && durationMin == null) {
            trace?.add("no distance and no duration → not an offer")
            return null
        }

        // Plausibility gate — a rate that makes no sense means we picked the
        // wrong number (or the wrong screen), not that this was a great offer.
        // Distances are km and the bounds are USD, so normalize through the
        // card's own currency first ("at 360 Ft/USD" in the trace).
        // Only trust the configured code when the amount came through the
        // configured symbol: a "$" card seen by a CNY user is still USD.
        val scale = Currencies.usdScale(currency, currencyCode.takeIf { currency == extraSymbol })
        val perKm = distanceKm?.takeIf { it > 0 }?.let { payout / it / scale }
        val perHour = durationMin?.takeIf { it > 0 }?.let { payout * 60.0 / it / scale }
        val noted = if (scale != 1.0) " at %.0f %s/USD".format(scale, currency) else ""
        // The gate's bounds are USD/km, but the decision trail is read by the
        // driver: quote the rate in the unit they picked, or a miles setting
        // reports a per-km figure that matches nothing on the card.
        val unitLabel = if (unit == "KM") "km" else "mi"
        fun quotedRate(usdPerKm: Double) = if (unit == "KM") usdPerKm else usdPerKm * 1.609344
        if ((perKm != null && perKm !in PLAUSIBLE_PER_KM) ||
            (perHour != null && perHour !in PLAUSIBLE_PER_HOUR)
        ) {
            trace?.add(
                "implausible$noted: ${perKm?.let { "%.2f".format(quotedRate(it)) }} USD/$unitLabel, " +
                    "${perHour?.let { "%.1f".format(it) }} USD/hr → not an offer",
            )
            return null
        }
        trace?.add(
            "rate ok$noted: ${perKm?.let { "%.2f".format(quotedRate(it)) }} USD/$unitLabel, " +
                "${perHour?.let { "%.1f".format(it) }} USD/hr",
        )

        fun nodeMatching(re: Regex, clickableOnly: Boolean): NodeRef? =
            nodes.firstOrNull { n ->
                re.containsMatchIn(n.text) && (!clickableOnly || n.clickable) && n.bounds.width() > 0
            }?.let { it.ref() }

        // Uber/Lyft accept the request when *anywhere* on the card is tapped —
        // there is no labelled Accept control, so the affordance is the card
        // itself: the smallest clickable node containing the payout text.
        fun tapAnywhereAccept(payoutNode: FlatNode?): NodeRef? {
            if (pkg !in TAP_ANYWHERE_PKGS) return null
            val m = payoutNode ?: return null
            val cx = m.bounds.centerX(); val cy = m.bounds.centerY()
            return nodes
                .filter { it.clickable && it.bounds.contains(cx, cy) }
                .minByOrNull { it.bounds.width() * it.bounds.height() }
                ?.let { it.ref() }
        }

        val isReservation = texts.any { RESERVATION.containsMatchIn(it) }

        val taughtAccept = hint?.accept?.let { a -> ParseHints.resolve(nodes, a) }
        val taughtDecline = hint?.decline?.let { a -> ParseHints.resolve(nodes, a) }

        val accept = if (unavailable) {
            trace?.add("card no longer available → no accept")
            null
        } else {
            taughtAccept?.let { it.ref() }
                ?: nodeMatching(ACCEPT_WORDS, clickableOnly = true)
                ?: nodeMatching(ACCEPT_WORDS, clickableOnly = false)
                ?: if (isReservation && pkg == "com.amazon.rabbit") {
                    nodeMatching(RESERVE_ACCEPT, clickableOnly = true)
                        ?: nodeMatching(RESERVE_ACCEPT, clickableOnly = false)
                } else null
                ?: tapAnywhereAccept(payoutMoney?.node)
        }
        // Every control that can say no, best first: a card with both an
        // explicit Decline and a close X must offer the Decline, not whichever
        // the tree happens to list first.
        val noControls = declineCandidates(nodes, pkg, payoutMoney?.node, !unavailable)
        val decline = taughtDecline?.let { it.ref() } ?: noControls.firstOrNull()
        val declineList = if (decline == null) noControls
        else listOf(decline) + noControls.filter { it.bounds != decline.bounds }

        trace?.add(
            "accept=${accept?.label ?: "none"} decline=${decline?.label ?: "none"}" +
                if (declineList.size > 1) " (${declineList.size} no-controls)" else "",
        )

        return DetectedOffer(
            appPackage = pkg,
            payoutCents = (payout * 100).toInt(),
            tipCents = tip?.let { (it * 100).toInt() },
            distanceKm = distanceKm?.let { Math.round(it * 10) / 10.0 },
            durationMin = durationMin,
            extraDistanceKm = null,
            acceptNode = accept,
            declineNode = decline,
            declineNodes = declineList,
            // Spark opens an order from its payout label; a taught payout is
            // just as good an entry point when the label wasn't recognised.
            payoutNode = payoutMoney?.node?.ref() ?: taughtPayout?.ref(),
            rawTexts = texts,
            currencySymbol = currency,
            currencyCode = currencyCode.takeIf { currency == extraSymbol },
            expiresInSec = expiresInSec,
            isReservation = isReservation,
        )
    }

    // --- Parsing rules shared by the heuristic and taught paths --------------

    private fun num(s: String): Double? = s.replace(',', '.').toDoubleOrNull()

    private fun FlatNode.ref() = NodeRef(bounds, text, clickable)

    private fun distanceOf(text: String): Double? =
        MILES.find(text)?.groupValues?.get(1)?.let(::num)?.times(1.609344)
            ?: KM.find(text)?.groupValues?.get(1)?.let(::num)

    private fun durationOf(text: String): Double? {
        if (TIMER_HINTS.containsMatchIn(text)) return null
        return HOURS_MIN.find(text)?.let { m ->
            val h = m.groupValues[1].toDouble()
            // Group 2 is the unit word ("hr", "óra"); minutes digits
            // live in group 3.
            val mnt = m.groupValues.getOrNull(3)?.toDoubleOrNull() ?: 0.0
            h * 60 + mnt
        } ?: MINUTES.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    // --- Taught nodes --------------------------------------------------------

    /** Loosest number a taught node can hold: "1 850 Ft", "12,4", "22". */
    private fun lenientNumber(text: String): Double? =
        Regex("""\d+(?:[.,]\d+)?""").find(text.replace(" ", ""))?.value
            ?.replace(',', '.')?.toDoubleOrNull()

    private fun lenientMoney(text: String, symbol: String): Pair<Double, String>? {
        val raw = Regex("""\d[\d\s.,'\u00A0]*\d|\d""").find(text)?.value ?: return null
        return parseAmount(raw)?.let { it to symbol }
    }

    /** A taught distance row. A bare number is in the app's unit — miles by
     *  default, since a US card shows "8.2" and means 8.2 mi — and the parser
     *  works in km, so miles are converted here. */
    private fun lenientDistance(text: String, unit: String): Double? =
        distanceOf(text) ?: lenientNumber(text)?.let { if (unit == "KM") it else it * 1.609344 }

    private fun lenientDuration(text: String): Double? {
        durationOf(text)?.let { return it }
        // A card showing "0:22" where a duration belongs.
        val clock = Regex("""^(\d{1,2}):(\d{2})$""").find(text.trim())
        if (clock != null) {
            return clock.groupValues[1].toDouble() * 60 + clock.groupValues[2].toDouble()
        }
        return lenientNumber(text) // bare number = minutes, the usual unit
    }
}
