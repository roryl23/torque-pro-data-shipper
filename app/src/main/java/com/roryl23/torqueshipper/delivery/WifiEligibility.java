package com.roryl23.torqueshipper.delivery;

/** Fail-closed rules for sending over the currently selected network. */
public final class WifiEligibility {
    private WifiEligibility() { }

    public static boolean permits(boolean wifiOnly, String requestedSsid, boolean connectedWifi,
                                  boolean hasFineLocation, boolean locationEnabled,
                                  String reportedSsid) {
        if (!wifiOnly) return true;
        if (!connectedWifi) return false;
        if (requestedSsid == null || requestedSsid.isEmpty()) return true;
        if (!hasFineLocation || !locationEnabled || reportedSsid == null
                || reportedSsid.equals("<unknown ssid>")) return false;
        // Android's WifiInfo wraps a printable SSID in quotes; do not trim or compare
        // case-insensitively, since either would select a different access point.
        return reportedSsid.length() == requestedSsid.length() + 2
                && reportedSsid.charAt(0) == '"'
                && reportedSsid.charAt(reportedSsid.length() - 1) == '"'
                && reportedSsid.regionMatches(1, requestedSsid, 0, requestedSsid.length());
    }
}
