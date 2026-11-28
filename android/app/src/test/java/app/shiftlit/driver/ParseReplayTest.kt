package app.shiftlit.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays captured screens through the parser.
 *
 * Two of these are the teach-mode safety net: a hint has to rescue a screen the
 * heuristics miss, and it must never change — or break — one they already read.
 */
class ParseReplayTest {

    @Test
    fun forintCardSurvivesThePlausibilityGate() {
        val offer = OfferParser.parse(ReplayFixtures.woltHu.pkg, ReplayFixtures.woltHu.nodes)
        assertNotNull("1 850 Ft for 12,4 km was thrown away by the gate", offer)
        val o = offer!!
        assertEquals(185_000, o.payoutCents)
        assertEquals("Ft", o.currencySymbol)
        assertEquals(12.4, o.distanceKm!!, 0.001)
        assertEquals(22.0, o.durationMin!!, 0.001)
    }

    @Test
    fun cnyResolvesByIsoCodeNotByTheYenSymbol() {
        val f = ReplayFixtures.cny
        val extra = OfferParser.extraMoney("CNY", "¥")
        val offer = OfferParser.parse(f.pkg, f.nodes, extra, "¥", "CNY")
        assertNotNull("¥40 for 10 km must not be read as 40 yen", offer)
        val o = offer!!
        assertEquals(4_000, o.payoutCents)
        assertEquals("¥", o.currencySymbol)
        assertEquals("CNY", o.currencyCode)
        assertEquals(10.0, o.distanceKm!!, 0.001)
        // The contract this rests on: "¥" alone resolves to the first
        // registered entry (JPY); the configured code is what disambiguates.
        assertEquals(7.2, Currencies.usdScale("¥", "CNY"), 0.001)
        assertEquals(150.0, Currencies.usdScale("¥"), 0.001)
    }

    @Test
    fun storedHistoryResolvesItsCurrency() {
        // New rows carry the code.
        assertEquals("CNY", Currencies.codeFor("¥", "CNY", "¥", "CNY"))
        // Old rows didn't: fall back to the setting when the symbol is the
        // configured one, and stay null for a foreign card.
        assertEquals("CNY", Currencies.codeFor("¥", null, "¥", "CNY"))
        assertNull(Currencies.codeFor("$", null, "¥", "CNY"))
        // A stored code outlives a settings change.
        assertEquals("EUR", Currencies.codeFor("€", "EUR", "¥", "CNY"))
        // End to end: how the feed renders a CNY row written before codes.
        assertEquals(
            "¥40.00",
            Currencies.format(4_000, "¥", Currencies.codeFor("¥", null, "¥", "CNY")),
        )
    }

    @Test
    fun formattingAndSpeechFollowTheIsoCodeToo() {
        // JPY writes whole yen and is spoken as "yen"; CNY has cents.
        assertEquals("¥40", Currencies.format(4_000, "¥", "JPY"))
        assertEquals("¥40.00", Currencies.format(4_000, "¥", "CNY"))
        assertEquals("40 yen", Currencies.spokenAmount(4_000, "¥", "JPY"))
        assertEquals("40.00 yuan", Currencies.spokenAmount(4_000, "¥", "CNY"))
        // Given only the ambiguous symbol, the old wrong answer is what remains.
        assertEquals("¥40", Currencies.format(4_000, "¥"))
        assertEquals("40 yen", Currencies.spokenAmount(4_000, "¥"))
    }

    @Test
    fun milesConvertAndTheUsdCardStillParses() {
        val offer = OfferParser.parse(ReplayFixtures.uberUs.pkg, ReplayFixtures.uberUs.nodes)
        assertNotNull(offer)
        val o = offer!!
        assertEquals(1_240, o.payoutCents)
        assertEquals(13.2, o.distanceKm!!, 0.05)
        assertEquals(24.0, o.durationMin!!, 0.001)
    }

    @Test
    fun receiptSizedPayoutIsStillRejected() {
        assertNull(
            "a \$1,850 customer total must not read as a payout",
            OfferParser.parse(ReplayFixtures.customerTotal.pkg, ReplayFixtures.customerTotal.nodes),
        )
    }

    @Test
    fun taughtHintRescuesABareNumberScreen() {
        val f = ReplayFixtures.bareNumbers
        assertNull(
            "this screen is supposed to miss without a hint",
            OfferParser.parse(f.pkg, f.nodes),
        )
        fun at(text: String) = ParseHints.anchor(f.nodes, f.nodes.indexOfFirst { it.text == text })!!
        val hint = ParseHints.Hint(
            pkg = f.pkg,
            screen = ParseHints.screenKey(f.nodes),
            payout = at("1 850 Ft"),
            distance = at("12,4"),
            duration = at("22"),
        )
        val offer = OfferParser.parse(f.pkg, f.nodes, hint = hint, unit = "KM")
        assertNotNull("the taught mapping should read this screen", offer)
        val o = offer!!
        assertEquals(185_000, o.payoutCents)
        assertEquals(12.4, o.distanceKm!!, 0.001)
        assertEquals(22.0, o.durationMin!!, 0.001)
        assertEquals("Ft", o.currencySymbol)
    }

    @Test
    fun anchorsStayUniqueWhenOneIdCoversManyRows() {
        fun row(t: String) = OfferParser.FlatNode(t, "app:id/row", Bounds(0, 0, 400, 48), false)
        val nodes = listOf(row("A"), row("B"), row("C"))
        val first = ParseHints.anchor(nodes, 0)!!
        val third = ParseHints.anchor(nodes, 2)!!
        assertNotEquals("one id must not give every row the same anchor", first, third)
        assertEquals("A", ParseHints.resolve(nodes, first)?.text)
        assertEquals("C", ParseHints.resolve(nodes, third)?.text)
    }

    @Test
    fun taughtButtonsApplyEvenWhenTheAmountsParse() {
        // A card with no labelled accept/decline — the × is an unlabeled,
        // id-less clickable node.
        val x = OfferParser.FlatNode("", null, Bounds(1000, 20, 1080, 100), true)
        val nodes = listOf(
            OfferParser.FlatNode("1 850 Ft", "app:id/payout", Bounds(0, 100, 800, 200), false),
            OfferParser.FlatNode("12,4 km", null, Bounds(0, 200, 800, 260), false),
            OfferParser.FlatNode("22 perc", null, Bounds(0, 260, 800, 320), false),
            x,
        )
        val pkg = "com.wolt.driver"
        val plain = OfferParser.parse(pkg, nodes)!!
        assertEquals(185_000, plain.payoutCents)
        assertNull("an unnamed × is invisible to the label search", plain.declineNode)

        val hint = ParseHints.Hint(
            pkg = pkg,
            screen = ParseHints.screenKey(nodes),
            decline = ParseHints.anchor(nodes, nodes.lastIndex),
        )
        val taught = OfferParser.parse(pkg, nodes, hint = hint)!!
        assertNotNull("the taught × must reach the offer", taught.declineNode)
        assertEquals("taught buttons never touch the price", 185_000, taught.payoutCents)
    }

    @Test
    fun unlabeledControlsAreTaughtByPosition() {
        val accept = OfferParser.FlatNode("Accept", "app:id/accept", Bounds(0, 400, 400, 480), true)
        val x = OfferParser.FlatNode("", null, Bounds(1000, 20, 1080, 100), true)
        val nodes = listOf(accept, x)
        val anchor = ParseHints.anchor(nodes, 1)!!
        assertTrue("an unlabeled node has no id to anchor on: $anchor", anchor.startsWith("pos:"))
        assertEquals(x.bounds, ParseHints.resolve(nodes, anchor)?.bounds)
        assertNull(
            "a control that moved is not the one that was taught",
            ParseHints.resolve(nodes - x, anchor),
        )
    }

    @Test
    fun taughtBareDistancesFollowTheConfiguredUnit() {
        val f = ReplayFixtures.bareNumbers
        val hint = ParseHints.Hint(
            pkg = f.pkg,
            screen = ParseHints.screenKey(f.nodes),
            payout = ParseHints.anchor(f.nodes, f.nodes.indexOfFirst { it.text == "1 850 Ft" }),
            distance = ParseHints.anchor(f.nodes, f.nodes.indexOfFirst { it.text == "12,4" }),
        )
        // A bare "12,4" on a US card is 12.4 mi; the parser stores km.
        val mi = OfferParser.parse(f.pkg, f.nodes, hint = hint, unit = "MI")!!
        assertEquals(20.0, mi.distanceKm!!, 0.001)
        val km = OfferParser.parse(f.pkg, f.nodes, hint = hint, unit = "KM")!!
        assertEquals(12.4, km.distanceKm!!, 0.001)
    }

    @Test
    fun trailingDollarCardsParseWithoutAConfiguredCurrencyRegex() {
        // The built-in matcher only knew a prefixed "$", so "18,50 $" fell
        // through entirely and the card read as having no money on it.
        val nodes = listOf(
            OfferParser.FlatNode("18,50 $", null, Bounds(0, 0, 800, 80), false),
            OfferParser.FlatNode("12,4 km", null, Bounds(0, 80, 800, 140), false),
            OfferParser.FlatNode("22 perc", null, Bounds(0, 140, 800, 200), false),
        )
        val offer = OfferParser.parse("app.test", nodes)
        assertNotNull("a trailing $ must match the built-in money matcher", offer)
        assertEquals(1_850, offer!!.payoutCents)
    }

    @Test
    fun unlabeledNodesDoNotShiftIndexAnchorsOrTextKeys() {
        fun labeled(t: String) = OfferParser.FlatNode(t, null, Bounds(0, 0, 10, 10), false)
        fun unlabeled() = OfferParser.FlatNode("", null, Bounds(0, 0, 10, 10), true)
        val plain = listOf(labeled("A"), labeled("B"))
        val withBlank = listOf(labeled("A"), unlabeled(), labeled("B"))
        assertEquals(
            "a blank node must not change a text-only screen key",
            ParseHints.screenKey(plain),
            ParseHints.screenKey(withBlank),
        )
        assertEquals(
            "an index anchor saved before blanks were listed must still land",
            "B",
            ParseHints.resolve(withBlank, ParseHints.anchor(plain, 1))?.text,
        )
    }

    @Test
    fun positionAnchorsTolerateASmallShift() {
        val taught = "pos:1000,20,1080,100"
        val shifted = listOf(OfferParser.FlatNode("", null, Bounds(996, 24, 1076, 104), true))
        assertEquals(
            "a control nudged by the card's insets must still resolve",
            shifted[0],
            ParseHints.resolve(shifted, taught),
        )
        val elsewhere = listOf(OfferParser.FlatNode("", null, Bounds(0, 900, 80, 980), true))
        assertNull(
            "a control in another corner is not the one that was taught",
            ParseHints.resolve(elsewhere, taught),
        )
    }

    @Test
    fun bogusHintCannotFabricateAnOffer() {
        val f = ReplayFixtures.bareNumbers
        // The title node holds no amount, so a hint pointing at it is useless —
        // the parser must not fall back to some other number on the screen.
        val hint = ParseHints.Hint(
            pkg = f.pkg,
            screen = ParseHints.screenKey(f.nodes),
            payout = ParseHints.anchor(f.nodes, 0), // the "New order" title
        )
        assertNull(OfferParser.parse(f.pkg, f.nodes, hint = hint))
    }

    @Test
    fun hintNeverChangesAScreenThatAlreadyParses() {
        for (f in listOf(ReplayFixtures.woltHu, ReplayFixtures.uberUs)) {
            val baseline = OfferParser.parse(f.pkg, f.nodes)
            assertNotNull("baseline must parse: ${f.name}", baseline)
            val b = baseline!!
            f.nodes.indices.forEach { i ->
                val anchor = ParseHints.anchor(f.nodes, i)!!
                val hint = ParseHints.Hint(
                    pkg = f.pkg,
                    screen = ParseHints.screenKey(f.nodes),
                    payout = anchor,
                    distance = anchor,
                    duration = anchor,
                )
                val got = OfferParser.parse(f.pkg, f.nodes, hint = hint)
                assertNotNull("${f.name}: hint on node $i killed the parse", got)
                val g = got!!
                assertEquals("${f.name}: payout changed by hint $i", b.payoutCents, g.payoutCents)
                assertEquals("${f.name}: distance changed by hint $i", b.distanceKm!!, g.distanceKm!!, 0.001)
                assertEquals("${f.name}: duration changed by hint $i", b.durationMin!!, g.durationMin!!, 0.001)
            }
        }
    }

    @Test
    fun screenKeysDistinguishLayoutsAndRespectOrderWithoutIds() {
        val a = ReplayFixtures.woltHu.nodes
        val b = ReplayFixtures.uberUs.nodes
        assertNotEquals(ParseHints.screenKey(a), ParseHints.screenKey(b))
        // View ids identify the layout, so node order must not matter.
        assertEquals(ParseHints.screenKey(a), ParseHints.screenKey(a.reversed()))
        // Without ids the key is the wording skeleton, and order matters there
        // because the anchors are positional.
        val noIds = a.map { it.copy(viewId = null) }
        assertNotEquals(ParseHints.screenKey(noIds), ParseHints.screenKey(noIds.reversed()))
    }

    @Test
    fun anchorsResolveToTheNodeTheyPointAt() {
        val nodes = ReplayFixtures.woltHu.nodes
        assertEquals("1 850 Ft", ParseHints.resolve(nodes, ParseHints.anchor(nodes, 1))?.text)
        assertEquals("22 perc", ParseHints.resolve(nodes, "idx:3")?.text)
        // Pre-fix anchors carried no occurrence and are no longer resolvable.
        assertNull(ParseHints.resolve(nodes, "id:com.wolt.driver:id/payout"))
        assertNull(ParseHints.resolve(nodes, "id:com.wolt.driver:id/gone@0"))
        assertNull(ParseHints.resolve(nodes, "idx:99"))
    }

    @Test
    fun preFixAnchorsGoBackToAMissInsteadOfGuessingARow() {
        // Two amounts share one id — the layout that made the old anchor pick a
        // row at random. Bare numbers elsewhere keep the heuristics out, so the
        // hint is the only thing that can produce an offer here.
        val nodes = listOf(
            OfferParser.FlatNode("1 200 Ft", "app:id/amount", Bounds(0, 0, 400, 48), false),
            OfferParser.FlatNode("3 600 Ft", "app:id/amount", Bounds(0, 48, 400, 96), false),
            OfferParser.FlatNode("12,4", "app:id/km", Bounds(0, 96, 400, 144), false),
            OfferParser.FlatNode("25", "app:id/min", Bounds(0, 144, 400, 192), false),
        )
        val pkg = "app.test"
        assertNull(
            "an id-only anchor must not resolve to a row",
            ParseHints.resolve(nodes, "id:app:id/amount"),
        )
        val stale = ParseHints.Hint(
            pkg = pkg,
            screen = ParseHints.screenKey(nodes),
            payout = "id:app:id/amount",
            distance = "id:app:id/km",
            duration = "id:app:id/min",
        )
        assertNull(
            "a stale payout anchor must miss rather than score the first amount",
            OfferParser.parse(pkg, nodes, hint = stale),
        )
        assertEquals(
            "the sheet must be able to say which part is out of date",
            "its payout was saved before rows could be told apart",
            ParseHints.staleReason(stale, nodes),
        )
        val trail = ArrayList<String>()
        OfferParser.parse(pkg, nodes, trace = trail, hint = stale)
        assertTrue(
            "the report should explain the miss, got $trail",
            trail.any { it.contains("saved mapping is out of date") },
        )
        // With occurrences recorded, the same screen teaches correctly.
        val fresh = stale.copy(
            payout = ParseHints.anchor(nodes, 1),
            distance = ParseHints.anchor(nodes, 2),
            duration = ParseHints.anchor(nodes, 3),
        )
        val offer = OfferParser.parse(pkg, nodes, hint = fresh, unit = "KM")
        assertNotNull(offer)
        assertEquals(360_000, offer!!.payoutCents)
        assertNull("a fresh mapping is not out of date", ParseHints.staleReason(fresh, nodes))
    }

    @Test
    fun staleReasonExplainsAReTargetThatLeft() {
        val nodes = listOf(
            OfferParser.FlatNode("3 600 Ft", "app:id/amount", Bounds(0, 0, 400, 48), false),
        )
        val gone = ParseHints.Hint(
            pkg = "app.test",
            screen = ParseHints.screenKey(nodes),
            payout = "id:app:id/amount@4",
        )
        assertEquals(
            "a mapping whose row is gone must say so",
            "the payout it pointed at is gone from this screen",
            ParseHints.staleReason(gone, nodes),
        )
        assertEquals(
            "a legacy button anchor is just as stale",
            "its accept was saved before rows could be told apart",
            ParseHints.staleReason(gone.copy(payout = null, accept = "id:app:id/accept"), nodes),
        )
        assertNull(
            "a mapping whose anchors all resolve is not stale",
            ParseHints.staleReason(gone.copy(payout = "id:app:id/amount@0"), nodes),
        )
    }

    @Test
    fun sparksOfferSheetIsACardEvenWithItsEarningsTabAndRefreshBar() {
        // The live Spark home: an offers sheet over the map, the app's own
        // "Earnings" label in the tree, a refresh snackbar, and a countdown on
        // the card. This was rejected as an earnings summary, so no Spark
        // order was ever scored and its payout was never tapped.
        val trail = ArrayList<String>()
        val offer = OfferParser.parse(
            "com.walmart.sparkdriver",
            listOf(
                OfferParser.FlatNode("Earnings", null, Bounds(40, 40, 300, 90), false),
                OfferParser.FlatNode("Offers", null, Bounds(40, 400, 300, 450), false),
                OfferParser.FlatNode("until 1:00 PM", null, Bounds(40, 480, 500, 540), false),
                OfferParser.FlatNode("Just for you", null, Bounds(60, 600, 500, 660), false),
                OfferParser.FlatNode("0:28", null, Bounds(860, 600, 980, 680), false),
                OfferParser.FlatNode("$53.53", null, Bounds(60, 700, 500, 820), false),
                OfferParser.FlatNode("21 stops • 49.9 miles • 2 hrs, 19 min", null, Bounds(60, 900, 1000, 960), false),
                OfferParser.FlatNode("Reject", "com.walmart.sparkdriver:id/reject", Bounds(60, 1700, 1020, 1860), true),
                OfferParser.FlatNode("You have new offer(s) available.", null, Bounds(0, 2200, 700, 2280), false),
            ),
            trace = trail,
        )
        assertNotNull("a live countdown and a Reject control make this a card: $trail", offer)
        assertEquals(5353, offer!!.payoutCents)
        assertEquals(80.3, offer.distanceKm!!, 0.05)
        assertEquals(139.0, offer.durationMin!!, 0.1)
        assertEquals("Reject", offer.declineNode?.label)
        assertEquals(
            "the payout is the entry point that opens the order",
            Bounds(60, 700, 500, 820),
            offer.payoutNode?.bounds,
        )
        assertFalse(
            "no summary rejection survived",
            trail.any { it.contains("summary screen") },
        )
    }

    @Test
    fun anEarningsScreenIsNotAnOffer() {
        // "New offer $94": the day's total and "h online" sit above a trip
        // list, and every one of those figures is plausible on its own.
        val nodes = listOf(
            OfferParser.FlatNode("You've earned today", null, Bounds(0, 60, 900, 120), false),
            OfferParser.FlatNode("\$94.00", null, Bounds(40, 130, 400, 220), false),
            OfferParser.FlatNode("4.2 h online", null, Bounds(40, 240, 400, 290), false),
            OfferParser.FlatNode("Trip · \$12.40 · 8.2 mi", null, Bounds(40, 400, 900, 460), false),
            OfferParser.FlatNode("Trip · \$9.80 · 5.1 mi", null, Bounds(40, 470, 900, 530), false),
        )
        val trail = ArrayList<String>()
        assertNull(
            "a day's earnings must not read as an offer",
            OfferParser.parse("com.ubercab.driver", nodes, trace = trail),
        )
        assertTrue(
            "the summary guard, not an accident: $trail",
            trail.any { it.contains("summary screen") },
        )
        assertFalse(
            "teaching belongs on offers, not on a wallet",
            OfferParser.looksLikeOfferForTeaching(nodes),
        )
    }

    @Test
    fun aLocalizedTripListIsStillNotAnOffer() {
        // No English summary words: the screen is recognised by carrying a
        // list of trips rather than one.
        fun trip(fare: String, km: String, perc: String, y: Int) = listOf(
            OfferParser.FlatNode(fare, null, Bounds(0, y, 300, y + 40), false),
            OfferParser.FlatNode(km, null, Bounds(0, y + 40, 300, y + 80), false),
            OfferParser.FlatNode(perc, null, Bounds(300, y + 40, 600, y + 80), false),
        )
        val nodes = trip("3 200 Ft", "8,2 km", "22 perc", 0) +
            trip("2 900 Ft", "6,1 km", "19 perc", 100) +
            trip("4 100 Ft", "9,4 km", "25 perc", 200) +
            trip("1 750 Ft", "3,3 km", "12 perc", 300)
        val trail = ArrayList<String>()
        assertNull(OfferParser.parse("com.wolt.driver", nodes, trace = trail))
        assertTrue(
            "a trip list is caught by its structure: $trail",
            trail.any { it.contains("summary screen") },
        )
        assertFalse(OfferParser.looksLikeOfferForTeaching(nodes))
    }

    @Test
    fun anUnreadableOfferStillLooksWorthTeaching() {
        assertTrue(
            "request wording is what makes a miss teachable",
            OfferParser.looksLikeOfferForTeaching(ReplayFixtures.bareNumbers.nodes),
        )
    }

    @Test
    fun windowScoringPicksTheCardOverTheWalletBehindIt() {
        // The card: one amount plus request wording.
        val card = OfferParser.scoreScreen(ReplayFixtures.bareNumbers.nodes)
        // The wallet behind it: several amounts, no request wording at all.
        val wallet = OfferParser.scoreScreen(
            listOf(
                OfferParser.FlatNode("3 200 Ft", null, Bounds(0, 0, 300, 40), false),
                OfferParser.FlatNode("2 900 Ft", null, Bounds(0, 40, 300, 80), false),
                OfferParser.FlatNode("4 100 Ft", null, Bounds(0, 80, 300, 120), false),
            ),
        )
        assertTrue(
            "more money must not outrank request wording: $card vs $wallet",
            card.score > wallet.score,
        )
        assertTrue("the card carries request cues", card.cues > 0)
        assertEquals(
            "a window with no money can never be the card",
            0,
            OfferParser.scoreScreen(
                listOf(OfferParser.FlatNode("Promo", null, Bounds(0, 0, 10, 10), false)),
            ).score,
        )
    }

    @Test
    fun uberMatchButtonIsBoundOnALiveTripRadarCard() {
        val offer = OfferParser.parse(
            "com.ubercab.driver",
            listOf(
                OfferParser.FlatNode("$8.63", "app:id/payout", Bounds(0, 0, 400, 80), false),
                OfferParser.FlatNode("10.0 mi", null, Bounds(0, 80, 400, 140), false),
                OfferParser.FlatNode("28 min", null, Bounds(0, 140, 400, 200), false),
                OfferParser.FlatNode("Match", "app:id/match", Bounds(0, 300, 400, 380), true),
            ),
        )
        assertNotNull(offer)
        assertEquals("Match", offer!!.acceptNode?.label)
        assertTrue("Match is a real clickable control", offer.acceptNode?.clickable == true)
    }

    @Test
    fun uberUnnamedTopRightCloseIsMappedAsDecline() {
        // Bounds from the real Uber card: the close icon is a blank, id-less
        // clickable square inside the card's top-right; the map markers are
        // smaller unnamed clickables elsewhere on the screen.
        val x = OfferParser.FlatNode("", null, Bounds(840, 1144, 984, 1288), true)
        val offer = OfferParser.parse(
            "com.ubercab.driver",
            listOf(
                OfferParser.FlatNode("", null, Bounds(0, 0, 1080, 2340), true),
                OfferParser.FlatNode("", null, Bounds(48, 1096, 1032, 2148), true),
                OfferParser.FlatNode("$8.63", null, Bounds(96, 1241, 426, 1409), false),
                OfferParser.FlatNode("28 min (10.0 mi) total", null, Bounds(192, 1581, 726, 1665), false),
                OfferParser.FlatNode("Match", null, Bounds(96, 1956, 984, 2100), true),
                x,
                OfferParser.FlatNode("", "com.ubercab.driver:id/map_marker", Bounds(883, 276, 991, 471), true),
            ),
        )
        assertNotNull(offer)
        assertEquals("Match", offer!!.acceptNode?.label)
        assertEquals("the unnamed X must become the decline control", x.bounds, offer.declineNode?.bounds)
        assertEquals("decline (close X)", offer.declineNode?.label)
    }

    @Test
    fun uberHomeDepotCloseXIsMappedAsDecline() {
        // The live Home Depot card that was declined: $14.02, 57 min (21.4 mi).
        // Its close X is a blank, id-less 144px button at the card's top-right.
        val x = OfferParser.FlatNode("", null, Bounds(840, 937, 984, 1081), true)
        val offer = OfferParser.parse(
            "com.ubercab.driver",
            listOf(
                OfferParser.FlatNode("", null, Bounds(0, 0, 1080, 2340), true),
                OfferParser.FlatNode("", null, Bounds(48, 889, 1032, 2148), true),
                OfferParser.FlatNode("$14.02", null, Bounds(96, 1034, 489, 1202), false),
                OfferParser.FlatNode("57 min (21.4 mi) total", null, Bounds(192, 1374, 723, 1458), false),
                OfferParser.FlatNode("Accept", null, Bounds(96, 1800, 984, 1944), true),
                x,
            ),
        )
        assertNotNull(offer)
        assertEquals("the blank top-right X is the decline control", x.bounds, offer!!.declineNode?.bounds)
        assertEquals("decline (close X)", offer.declineNode?.label)
    }

    @Test
    fun matchedCardDoesNotExposeAnAcceptControl() {
        val offer = OfferParser.parse(
            "com.ubercab.driver",
            listOf(
                OfferParser.FlatNode("$8.63", "app:id/payout", Bounds(0, 0, 400, 80), false),
                OfferParser.FlatNode("10.0 mi", null, Bounds(0, 80, 400, 140), false),
                OfferParser.FlatNode("28 min", null, Bounds(0, 140, 400, 200), false),
                OfferParser.FlatNode(
                    "Another driver matched to this request",
                    null,
                    Bounds(0, 200, 500, 260),
                    false,
                ),
                OfferParser.FlatNode("Match", "app:id/match", Bounds(0, 300, 400, 380), true),
                OfferParser.FlatNode("", null, Bounds(840, 1144, 984, 1288), true),
            ),
        )
        assertNotNull(offer)
        assertNull("a matched card has nothing live to accept", offer!!.acceptNode)
        assertNull("and nothing live to decline", offer.declineNode)
    }

    @Test
    fun tripRadarPillTapsItsClickableParentNotTheBadge() {
        val nodes = listOf(
            OfferParser.FlatNode("", null, Bounds(0, 0, 1080, 2340), true),
            OfferParser.FlatNode("", null, Bounds(332, 1568, 747, 1712), true),
            OfferParser.FlatNode(
                "Trip Radar",
                "com.ubercab.driver:id/ub__driver_job_offers_pill_title",
                Bounds(380, 1604, 615, 1676),
                false,
            ),
            OfferParser.FlatNode("1", null, Bounds(645, 1616, 693, 1664), false),
        )
        assertEquals(Bounds(332, 1568, 747, 1712), OfferParser.tripRadarTapTarget(nodes))
    }

    @Test
    fun aScreenWithoutARadarPillHasNoTapTarget() {
        assertNull(
            OfferParser.tripRadarTapTarget(
                listOf(OfferParser.FlatNode("$20.09", null, Bounds(0, 0, 300, 80), false)),
            ),
        )
    }

    @Test
    fun windowScoringUsesTheConfiguredCurrency() {
        val nodes = listOf(
            OfferParser.FlatNode("KSh 1 200", null, Bounds(0, 0, 300, 40), false),
        )
        assertEquals(
            "a symbol outside the built-in set must not count on its own",
            0,
            OfferParser.scoreScreen(nodes).money,
        )
        val extra = OfferParser.extraMoney("KES", "KSh")
        assertNotNull(extra)
        assertEquals(
            "the configured symbol must score the same here as in the parser",
            1,
            OfferParser.scoreScreen(nodes, extra, "KSh").money,
        )
    }

    @Test
    fun hoursAndMinutesInOneLabelKeepTheMinutes() {
        // "2 hrs, 35 min" is how Uber prints a long trip. The comma used to
        // end the match at "2 hrs", so a 155-minute job scored as 120.
        val offer = OfferParser.parse(
            "com.ubercab.driver",
            listOf(
                OfferParser.FlatNode("$30.00", "app:id/fare", Bounds(0, 0, 400, 80), false),
                OfferParser.FlatNode("10 mi", null, Bounds(0, 80, 400, 140), false),
                OfferParser.FlatNode("2 hrs, 35 min", null, Bounds(0, 140, 400, 200), false),
            ),
        )
        assertNotNull(offer)
        assertEquals(155.0, offer!!.durationMin!!, 0.001)
    }

    @Test
    fun diagnosticsQuoteTheRateInTheConfiguredUnit() {
        val nodes = listOf(
            OfferParser.FlatNode("$20.00", null, Bounds(0, 0, 400, 80), false),
            OfferParser.FlatNode("10 mi", null, Bounds(0, 80, 400, 140), false),
            OfferParser.FlatNode("30 min", null, Bounds(0, 140, 400, 200), false),
        )
        val mi = ArrayList<String>()
        OfferParser.parse("com.ubercab.driver", nodes, trace = mi)
        assertTrue("miles mode must not report per km: $mi", mi.any { it.contains("2.00 USD/mi") })
        assertFalse("miles mode quoted a km rate: $mi", mi.any { it.contains("USD/km") })

        // 10 mi is 16.09 km, so $20 is $1.24/km.
        val km = ArrayList<String>()
        OfferParser.parse("com.ubercab.driver", nodes, trace = km, unit = "KM")
        assertTrue("km mode keeps per km: $km", km.any { it.contains("1.24 USD/km") })
    }

    @Test
    fun explicitDeclineBeatsANavigationClose() {
        // The card's Decline sits at the bottom; a "Close" on the navigation
        // bar above it used to win simply by being declared first in the tree.
        val decline = OfferParser.FlatNode("Decline", "app:id/decline", Bounds(80, 1800, 1000, 1944), true)
        val navClose = OfferParser.FlatNode("Close", "app:id/nav_close", Bounds(24, 24, 120, 120), true)
        val offer = OfferParser.parse(
            "com.doordash.driverapp",
            listOf(
                navClose,
                OfferParser.FlatNode("$9.50", null, Bounds(80, 600, 500, 760), false),
                OfferParser.FlatNode("4.2 mi", null, Bounds(80, 780, 400, 860), false),
                OfferParser.FlatNode("18 min", null, Bounds(80, 880, 400, 960), false),
                decline,
            ),
        )!!
        assertEquals(decline.bounds, offer.declineNode?.bounds)
        assertEquals(
            "the navigation close stays available as a fallback, after the Decline",
            listOf(decline.bounds, navClose.bounds),
            offer.declineNodes.map { it.bounds },
        )
    }

    @Test
    fun aCardsOwnCloseXBeatsABannerDismiss() {
        // Two no-controls in one tree: a promo banner's Dismiss above the card,
        // and the card's blank top-right X. Only the X declines the job.
        val x = OfferParser.FlatNode("", null, Bounds(840, 1144, 984, 1288), true)
        val banner = OfferParser.FlatNode("Dismiss", "app:id/promo_dismiss", Bounds(700, 420, 1000, 540), true)
        val offer = OfferParser.parse(
            "com.ubercab.driver",
            listOf(
                OfferParser.FlatNode("", null, Bounds(0, 0, 1080, 2340), true),
                OfferParser.FlatNode("", null, Bounds(48, 1096, 1032, 2148), true),
                OfferParser.FlatNode("$8.63", null, Bounds(96, 1241, 426, 1409), false),
                OfferParser.FlatNode("28 min (10.0 mi) total", null, Bounds(192, 1581, 726, 1665), false),
                OfferParser.FlatNode("Match", null, Bounds(96, 1956, 984, 2100), true),
                banner,
                x,
            ),
        )!!
        assertEquals("the card's own X must win", x.bounds, offer.declineNode?.bounds)
        assertEquals("both controls are kept for the retry", 2, offer.declineNodes.size)
        assertEquals(x.bounds, offer.declineNodes.first().bounds)
    }

    @Test
    fun sparkCardKeepsItsRejectButtonAndPayoutEntry() {
        val payout = OfferParser.FlatNode("$24.00", null, Bounds(60, 600, 500, 780), false)
        val reject = OfferParser.FlatNode("Reject", "com.walmart.sparkdriver:id/reject", Bounds(60, 1700, 1020, 1860), true)
        val offer = OfferParser.parse(
            "com.walmart.sparkdriver",
            listOf(
                payout,
                OfferParser.FlatNode("8.4 mi", null, Bounds(60, 820, 400, 900), false),
                OfferParser.FlatNode("42 min", null, Bounds(60, 920, 400, 1000), false),
                reject,
            ),
        )!!
        assertEquals(reject.bounds, offer.declineNode?.bounds)
        // Spark only lets the payout be tapped; it is how its order detail
        // opens before a reject.
        assertEquals(payout.bounds, offer.payoutNode?.bounds)
    }

    @Test
    fun sparksSecondRejectOfferIsStillADeclineControl() {
        // Spark declines in two taps: "Reject" on the card, then a confirm
        // sheet whose button says "Reject Offer". The sheet covers the card
        // and shows no payout, distance or duration.
        val target = OfferParser.confirmDeclineTarget(
            listOf(
                OfferParser.FlatNode("Keep Offer", "com.walmart.sparkdriver:id/keep", Bounds(60, 1500, 1020, 1640), true),
                OfferParser.FlatNode("Reject Offer", "com.walmart.sparkdriver:id/reject", Bounds(60, 1660, 1020, 1800), true),
            ),
        )
        assertEquals(Bounds(60, 1660, 1020, 1800), target?.bounds)
        assertEquals("Reject Offer", target?.label)
    }

    @Test
    fun sparksConfirmSheetIsReachableWithTheCardStillBehindIt() {
        // The confirm sheet can arrive in the same tree as the card that opened
        // it. The card's own REJECT is already tapped, so the retry must find
        // the sheet's REJECT OFFER instead of giving up on the tried control.
        val cardReject = OfferParser.FlatNode(
            "REJECT", "com.walmart.sparkdriver:id/reject", Bounds(60, 1700, 480, 1860), true,
        )
        val confirm = OfferParser.FlatNode(
            "REJECT OFFER", "com.walmart.sparkdriver:id/reject_offer", Bounds(60, 1660, 1020, 1800), true,
        )
        val targets = OfferParser.confirmDeclineTargets(listOf(cardReject, confirm))
        assertEquals("both refusals are listed", 2, targets.size)
        assertEquals(
            "the untried control is the confirm sheet",
            "REJECT OFFER",
            targets.first { it.bounds != cardReject.bounds }.label,
        )
    }

    @Test
    fun sparkDetailMustBeUpBeforeRejectIsTapped() {
        // The list row and the detail share the payout, distance, duration and
        // a REJECT label. Only the detail's copy proves the second screen is
        // actually pulled up.
        val row = listOf(
            OfferParser.FlatNode("$27.04", null, Bounds(60, 700, 500, 820), false),
            OfferParser.FlatNode("3 stops • 15.7 miles • 1 hr, 8 mins", null, Bounds(60, 900, 1000, 960), false),
            OfferParser.FlatNode("REJECT", null, Bounds(60, 2300, 480, 2380), true),
        )
        assertFalse("a list row is not the detail", OfferParser.isOrderDetail(row))
        assertTrue(
            "the detail's own copy verifies it",
            OfferParser.isOrderDetail(row + OfferParser.FlatNode("Estimated total", null, Bounds(60, 1100, 600, 1160), false)),
        )
    }

    @Test
    fun sparkConfirmIsOnlyPressedWhenItsDialogIsUp() {
        val confirm = OfferParser.FlatNode(
            "REJECT OFFER", "com.walmart.sparkdriver:id/reject_offer", Bounds(60, 1660, 1020, 1800), true,
        )
        assertNull(
            "a lone label is not a confirm sheet",
            OfferParser.confirmDeclineSheet(listOf(confirm)),
        )
        assertEquals(
            confirm.bounds,
            OfferParser.confirmDeclineSheet(
                listOf(
                    OfferParser.FlatNode(
                        "Are you sure you want to reject this offer?",
                        null, Bounds(60, 1200, 1000, 1400), false,
                    ),
                    confirm,
                ),
            )?.bounds,
        )
    }

    @Test
    fun sparksUnavailableDialogIsDismissedNotDeclined() {
        // Seen live after an order expired: a modal over the list whose only
        // control is GOT IT. It has to be cleared, or nothing behind it can be
        // seen or declined. It must never be treated as a refusal.
        val gotIt = OfferParser.FlatNode(
            "GOT IT", "com.walmart.sparkdriver:id/ack", Bounds(60, 1900, 1020, 2040), true,
        )
        val dialog = listOf(
            OfferParser.FlatNode("Offer unavailable", null, Bounds(60, 1200, 800, 1300), false),
            OfferParser.FlatNode(
                "This offer is no longer available because it either expired, got " +
                    "accepted by another driver, or the order(s) changed.",
                null, Bounds(60, 1350, 1000, 1600), false,
            ),
            gotIt,
        )
        assertEquals(
            gotIt.bounds,
            OfferParser.unavailableDismissTarget("com.walmart.sparkdriver", dialog),
        )
        assertNull(
            "only Spark's dialog is handled",
            OfferParser.unavailableDismissTarget("com.ubercab.driver", dialog),
        )
        assertNull(
            "an ordinary list is not dismissed",
            OfferParser.unavailableDismissTarget(
                "com.walmart.sparkdriver",
                listOf(
                    OfferParser.FlatNode("Offers", null, Bounds(0, 400, 300, 450), false),
                    gotIt,
                ),
            ),
        )
        assertNull(
            "the dialog copy without its control is not tapped",
            OfferParser.unavailableDismissTarget(
                "com.walmart.sparkdriver",
                dialog.dropLast(1),
            ),
        )
    }

    @Test
    fun sparksOfferListRefreshIsMatchedByItsViewId() {
        val action = OfferParser.FlatNode(
            "",
            "com.walmart.sparkdriver:id/snackbar_action",
            Bounds(760, 2080, 1020, 2200),
            true,
        )
        // The label is not in the tree at all; the id is what identifies it.
        assertEquals(
            action.bounds,
            OfferParser.stalledListAction("com.walmart.sparkdriver", listOf(action)),
        )
        // It shows while orders are still on the list, so a payout may be up.
        assertEquals(
            action.bounds,
            OfferParser.stalledListAction(
                "com.walmart.sparkdriver",
                listOf(
                    OfferParser.FlatNode("$18.00", null, Bounds(60, 400, 500, 560), false),
                    action,
                ),
            ),
        )
        assertNull(
            "only Spark's list is handled",
            OfferParser.stalledListAction("com.ubercab.driver", listOf(action)),
        )
        assertNull(
            "a different snackbar id is not the refresh",
            OfferParser.stalledListAction(
                "com.walmart.sparkdriver",
                listOf(action.copy(viewId = "com.walmart.sparkdriver:id/other_action")),
            ),
        )
    }

    @Test
    fun aCardWhoseOnlyNoIsLabelledCloseStillDeclines() {
        val close = OfferParser.FlatNode("Close", null, Bounds(900, 300, 1000, 400), true)
        val offer = OfferParser.parse(
            "com.instacart.shopper",
            listOf(
                close,
                OfferParser.FlatNode("$11.00", null, Bounds(60, 500, 500, 640), false),
                OfferParser.FlatNode("3.1 mi", null, Bounds(60, 680, 400, 740), false),
                OfferParser.FlatNode("14 min", null, Bounds(60, 780, 400, 840), false),
            ),
        )!!
        assertEquals("a labelled no is still a no", close.bounds, offer.declineNode?.bounds)
    }
}
