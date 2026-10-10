package com.roryl23.torqueshipper.delivery;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WifiEligibilityTest {
    @Test public void disabledGatePreservesUnrestrictedDelivery() {
        assertTrue(WifiEligibility.permits(false, "garage", false, false, false, null));
    }

    @Test public void anyWifiRequiresConnectedWifiButNoLocation() {
        assertFalse(WifiEligibility.permits(true, "", false, true, true, "\"home\""));
        assertFalse(WifiEligibility.permits(true, null, false, false, false, null));
        assertTrue(WifiEligibility.permits(true, "", true, false, false, null));
    }

    @Test public void exactSsidRequiresLocationPermissionAndServices() {
        assertFalse(WifiEligibility.permits(true, "home", true, false, true, "\"home\""));
        assertFalse(WifiEligibility.permits(true, "home", true, true, false, "\"home\""));
        assertFalse(WifiEligibility.permits(true, "home", false, true, true, "\"home\""));
        assertTrue(WifiEligibility.permits(true, "home", true, true, true, "\"home\""));
    }

    @Test public void unknownOrUnquotedSsidFailsClosed() {
        assertFalse(WifiEligibility.permits(true, "home", true, true, true, null));
        assertFalse(WifiEligibility.permits(true, "home", true, true, true, "<unknown ssid>"));
        assertFalse(WifiEligibility.permits(true, "home", true, true, true, "home"));
        assertFalse(WifiEligibility.permits(true, "home", true, true, true, "0x686f6d65"));
    }

    @Test public void ssidMatchesExactlyIncludingCaseAndWhitespace() {
        assertFalse(WifiEligibility.permits(true, "home", true, true, true, "\"Home\""));
        assertFalse(WifiEligibility.permits(true, "home", true, true, true, "\"home \""));
        assertTrue(WifiEligibility.permits(true, " home ", true, true, true, "\" home \""));
    }
}
