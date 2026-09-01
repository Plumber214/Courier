package courier.engine

import courier.model.OutputProfile
import courier.model.TranscodeCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FormatSelectorTest {

    private val presetHeights = listOf("1080p" to 1080, "720p" to 720, "480p" to 480, "360p" to 360)

    // Helper: splits format string into its /-separated alternatives
    private fun alternatives(formatArg: String): List<String> = formatArg.split("/")

    // Invariant 1: In EDITING_NATIVE, every alternative containing bestvideo also contains vcodec^=avc1
    @Test
    fun testEditingNativeEveryBestVideoHasAvc1() {
        val testPresets = listOf(null, "best", "1080p", "720p", "480p", "360p")
        for (preset in testPresets) {
            val formatArg = FormatSelector.videoFormatArg(preset, OutputProfile.EDITING_NATIVE)
            val alts = alternatives(formatArg)
            for (alt in alts) {
                if (alt.contains("bestvideo")) {
                    assertTrue(
                        alt.contains("vcodec^=avc1"),
                        "Alternative '$alt' in preset '$preset' contains bestvideo but lacks vcodec^=avc1 constraint"
                    )
                }
            }
        }
    }

    // Invariant 2: In EDITING_NATIVE, every alternative containing bestaudio also contains acodec^=mp4a
    @Test
    fun testEditingNativeEveryBestaudioHasMp4a() {
        val testPresets = listOf(null, "best", "1080p", "720p", "480p", "360p")
        for (preset in testPresets) {
            val formatArg = FormatSelector.videoFormatArg(preset, OutputProfile.EDITING_NATIVE)
            val alts = alternatives(formatArg)
            for (alt in alts) {
                if (alt.contains("bestaudio")) {
                    assertTrue(
                        alt.contains("acodec^=mp4a"),
                        "Alternative '$alt' in preset '$preset' contains bestaudio but lacks acodec^=mp4a constraint"
                    )
                }
            }
        }
    }

    // Invariant 3: No profile pairs an unconstrained-audio alternative with an mp4 merge target (§0.4 live bug)
    @Test
    fun testNoProfilePairsUnconstrainedAudioWithMp4MergeTarget() {
        for (profile in OutputProfile.entries) {
            val formatArg = FormatSelector.videoFormatArg("1080p", profile)
            val alts = alternatives(formatArg)
            val mergeContainer = FormatSelector.mergeOutputFormat(profile, null, TranscodeCodec.H264)
            val hasUnconstrainedAudio = alts.any { alt ->
                alt.contains("bestaudio") && !alt.contains("acodec^=mp4a")
            }
            if (hasUnconstrainedAudio) {
                assertEquals(
                    "mkv",
                    mergeContainer,
                    "Profile $profile allows unconstrained audio (Opus fallback) but targets $mergeContainer instead of mkv"
                )
            }
        }
    }

    // Invariant 4: Each preset height appears as [height<=N] in every alternative that filters
    @Test
    fun testEachPresetHeightAppearsAsHeightConstraint() {
        for ((preset, expectedHeight) in presetHeights) {
            for (profile in OutputProfile.entries) {
                val formatArg = FormatSelector.videoFormatArg(preset, profile)
                val alts = alternatives(formatArg)
                for (alt in alts) {
                    if (alt.contains("height<=")) {
                        assertTrue(
                            alt.contains("[height<=$expectedHeight]"),
                            "Alternative '$alt' in profile $profile does not contain expected [height<=$expectedHeight]"
                        )
                    }
                }
            }
        }
    }

    // Invariant 5: A raw ladder expression (contains + or /) passes through unmodified
    @Test
    fun testRawLadderExpressionPassesThroughUnmodified() {
        val rawExpressions = listOf(
            "299+140/best",
            "399+140/398+140",
            "bestvideo[height<=1080]+bestaudio/best"
        )
        for (raw in rawExpressions) {
            for (profile in OutputProfile.entries) {
                val result = FormatSelector.videoFormatArg(raw, profile)
                assertEquals(raw, result, "Raw ladder expression must pass through unmodified for $profile")
            }
        }
    }

    // Invariant 6: needsTranscode is false when selectedVcodec is already avc1 and target is H264
    @Test
    fun testNeedsTranscodeFalseWhenAlreadyAvc1AndH264Target() {
        assertFalse(
            FormatSelector.needsTranscode(OutputProfile.EDITING_TRANSCODE, "avc1.64002a", TranscodeCodec.H264),
            "Should not transcode when source is already avc1 and target is H264"
        )
        assertFalse(
            FormatSelector.needsTranscode(OutputProfile.EDITING_TRANSCODE, "h264", TranscodeCodec.H264),
            "Should not transcode when source is already h264 and target is H264"
        )
    }

    // Invariant 7: needsTranscode is true when selectedVcodec is null and profile is EDITING_TRANSCODE
    @Test
    fun testNeedsTranscodeTrueWhenSelectedVcodecNullAndEditingTranscode() {
        assertTrue(
            FormatSelector.needsTranscode(OutputProfile.EDITING_TRANSCODE, null, TranscodeCodec.H264),
            "Should transcode when source codec is unknown under EDITING_TRANSCODE"
        )
    }

    // Invariant 8: needsTranscode is always true for ProRes/DNxHR targets
    @Test
    fun testNeedsTranscodeAlwaysTrueForProResAndDNxHR() {
        assertTrue(
            FormatSelector.needsTranscode(OutputProfile.EDITING_TRANSCODE, "avc1.64002a", TranscodeCodec.PRORES),
            "ProRes target must always transcode even from avc1"
        )
        assertTrue(
            FormatSelector.needsTranscode(OutputProfile.EDITING_TRANSCODE, "avc1.64002a", TranscodeCodec.DNXHR),
            "DNxHR target must always transcode even from avc1"
        )
    }

    // Invariant 9: mergeOutputFormat returns mkv when needsTranscode is true or profile is MAX_QUALITY
    @Test
    fun testMergeOutputFormatReturnsMkvWhenNeedsTranscodeOrMaxQuality() {
        // Transcode needed -> mkv intermediate
        assertEquals("mkv", FormatSelector.mergeOutputFormat(OutputProfile.EDITING_TRANSCODE, null, TranscodeCodec.H264))
        assertEquals("mkv", FormatSelector.mergeOutputFormat(OutputProfile.EDITING_TRANSCODE, "av01.0.08M.08", TranscodeCodec.H264))
        
        // Transcode not needed (source already H.264) -> mp4
        assertEquals("mp4", FormatSelector.mergeOutputFormat(OutputProfile.EDITING_TRANSCODE, "avc1.64002a", TranscodeCodec.H264))

        // MAX_QUALITY -> mkv (holds Opus / AV1 safely)
        assertEquals("mkv", FormatSelector.mergeOutputFormat(OutputProfile.MAX_QUALITY, null, TranscodeCodec.H264))

        // EDITING_NATIVE -> mp4
        assertEquals("mp4", FormatSelector.mergeOutputFormat(OutputProfile.EDITING_NATIVE, "avc1.64002a", TranscodeCodec.H264))
    }

    // Invariant 10: audioNormalisationArgs is empty for MAX_QUALITY, non-empty otherwise
    @Test
    fun testAudioNormalisationArgsEmptyForMaxQualityNonEmptyOtherwise() {
        assertTrue(
            FormatSelector.audioNormalisationArgs(OutputProfile.MAX_QUALITY).isEmpty(),
            "MAX_QUALITY must leave audio streams untouched (empty normalisation args)"
        )
        assertTrue(
            FormatSelector.audioNormalisationArgs(OutputProfile.EDITING_NATIVE).isNotEmpty(),
            "EDITING_NATIVE must normalise audio to 48 kHz"
        )
        assertTrue(
            FormatSelector.audioNormalisationArgs(OutputProfile.EDITING_TRANSCODE).isNotEmpty(),
            "EDITING_TRANSCODE must normalise audio when not transcoding"
        )
    }

    // Invariant 11: Every transcodeArgs variant contains -ar 48000
    @Test
    fun testEveryTranscodeArgsContains48kHz() {
        for (codec in TranscodeCodec.entries) {
            val args = FormatSelector.transcodeArgs(codec).joinToString(" ")
            assertTrue(
                args.contains("-ar 48000"),
                "Transcode args for $codec must contain -ar 48000"
            )
        }
    }

    // Invariant 12: Every transcodeArgs variant contains -fps_mode cfr
    @Test
    fun testEveryTranscodeArgsContainsCfr() {
        for (codec in TranscodeCodec.entries) {
            val args = FormatSelector.transcodeArgs(codec).joinToString(" ")
            assertTrue(
                args.contains("-fps_mode cfr"),
                "Transcode args for $codec must contain -fps_mode cfr to prevent VFR drift"
            )
        }
    }

    // Documented exception guard (§2.3): EDITING_NATIVE's last two alternatives are deliberately unconstrained fallbacks
    @Test
    fun testEditingNativeDocumentedFallbackExceptions() {
        val formatArg = FormatSelector.videoFormatArg("1080p", OutputProfile.EDITING_NATIVE)
        val alts = alternatives(formatArg)
        assertTrue(alts.size >= 5, "EDITING_NATIVE should have at least 5 alternatives")
        
        // The first 3 alternatives are strictly constrained to AVC1/AAC
        for (i in 0..2) {
            assertTrue(alts[i].contains("avc1"), "Alternative $i ('${alts[i]}') must be constrained to avc1")
        }
        
        // The last 2 alternatives are documented site fallbacks (best[height<=N][ext=mp4], best[height<=N])
        assertEquals("best[height<=1080][ext=mp4]", alts[3], "Documented 4th alternative must be best[height<=N][ext=mp4]")
        assertEquals("best[height<=1080]", alts[4], "Documented 5th alternative must be best[height<=N]")
    }
}