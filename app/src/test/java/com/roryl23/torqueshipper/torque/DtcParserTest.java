package com.roryl23.torqueshipper.torque;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class DtcParserTest {
    @Test public void decodesFamiliesAndSkipsPadding() {
        assertEquals(Arrays.asList("P0133", "C1234", "B0567", "U0123"),
                DtcParser.decode(new String[]{"7E8 0B 43 01 33 52 34 85 67 C1 23 00 00"}, 3));
    }

    @Test public void ignoresEchoAndOtherDiagnosticModes() {
        assertEquals(Collections.singletonList("P0420"),
                DtcParser.decode(new String[]{"03\r7E8 03 43 04 20\rNO DATA\r7E8 03 47 01 33"}, 3));
    }

    @Test public void assemblesConsecutiveCanFramesAndDeduplicatesEcus() {
        assertEquals(Arrays.asList("P0133", "P0420", "U0123", "C1234"),
                DtcParser.decode(new String[]{"7E8 10 0A 43 04 01 33 04 20", "7E8 21 C1 23 52 34 00 00",
                        "7E9 03 43 01 33"}, 3));
    }

    @Test public void handlesSolidHexPayloadAndPermanentMode() {
        assertEquals(Collections.singletonList("P0300"),
                DtcParser.decode(new String[]{"7E8:034A0300"}, 10));
    }

    @Test public void decodesJoinedCanAddressAndPayload() {
        assertEquals(Collections.singletonList("P0133"),
                DtcParser.decode(new String[]{"7E803430133"}, 3));
    }

    @Test public void decodesModeThreeCanCountWithoutMistakingItForCode() {
        assertEquals(Collections.singletonList("P0133"),
                DtcParser.decode(new String[]{"7E8 04 43 01 01 33"}, 3));
        assertEquals(Collections.singletonList("P0133"),
                DtcParser.decode(new String[]{"43 01 33"}, 3)); // Count-free legacy.
    }

    @Test public void ignoresModeByteEmbeddedInsideOtherResponse() {
        String[] otherMode = {"47 43 01 33", "7E8 04 47 43 01 33"};
        assertEquals(Collections.emptyList(), DtcParser.decode(otherMode, 3));
        assertFalse(DtcParser.hasModeResponse(otherMode, 3));
    }

    @Test public void interleavedEcUsDoNotShareConsecutiveFrames() {
        String[] frames = {"7E8 10 08 43 03 01 33 04 20", "7E9 03 43 03 00",
                "7E8 21 C1 23 00 00"};
        assertEquals(Arrays.asList("P0300", "P0133", "P0420", "U0123"),
                DtcParser.decode(frames, 3));
        assertFalse(DtcParser.hasModeResponse(
                new String[]{"7E8 10 08 43 03 01 33 04 20", "7E9 21 C1 23 00 00"}, 3));
    }

    @Test public void leavesUncertainPendingAndPermanentCountLayoutsRaw() {
        assertEquals(Collections.emptyList(), DtcParser.decode(new String[]{"7E8 04 47 01 01 33"}, 7));
        assertTrue(DtcParser.parse(new String[]{"7E8 04 4A 01 01 33"}, 10).ambiguous);
        String[] unaddressedFrames = {"10 08 43 03 01 33 04 20", "21 C1 23 00 00"};
        assertEquals(Collections.emptyList(), DtcParser.decode(unaddressedFrames, 3));
        assertTrue(DtcParser.parse(unaddressedFrames, 3).ambiguous);
    }

    @Test public void doesNotInventCodesFromUnavailableReply() {
        assertEquals(Collections.emptyList(), DtcParser.decode(new String[]{"NO DATA", "?", "7E8"}, 7));
        assertFalse(DtcParser.hasModeResponse(new String[]{"NO DATA", "?", "7E8"}, 7));
        assertTrue(DtcParser.hasModeResponse(new String[]{"47 00 00"}, 7));
    }
}
