package com.roryl23.torqueshipper.delivery;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/** Chooses the active network for every queued POST, binding Wi-Fi-only requests to it. */
public final class WifiDeliveryGate implements DeliveryQueue.Transport {
    private final Context context;
    private final boolean wifiOnly;
    private final String requestedSsid;
    private final ConnectivityManager connectivity;
    private final WifiManager wifi;
    private final LocationManager location;

    public WifiDeliveryGate(Context context, boolean wifiOnly, String requestedSsid) {
        this.context = context.getApplicationContext();
        this.wifiOnly = wifiOnly;
        this.requestedSsid = requestedSsid;
        connectivity = this.context.getSystemService(ConnectivityManager.class);
        wifi = this.context.getSystemService(WifiManager.class);
        location = this.context.getSystemService(LocationManager.class);
    }

    /** Used by connectivity callbacks to request a drain only when delivery is possible. */
    public boolean isEligible() {
        if (!wifiOnly) return true;
        return eligibleWifiNetwork() != null;
    }

    @Override
    public HttpURLConnection open(URL endpoint) throws IOException {
        // Preserve unrestricted delivery when the preference is off, including cellular.
        if (!wifiOnly) {
            return (HttpURLConnection) endpoint.openConnection();
        }
        Network network = eligibleWifiNetwork();
        if (network == null || !network.equals(connectivity.getActiveNetwork())) {
            throw new IOException("No eligible Wi-Fi network");
        }
        // Never use URL.openConnection() here: a default-network switch could silently
        // send this request on cellular between the eligibility check and socket creation.
        return (HttpURLConnection) network.openConnection(endpoint);
    }

    private Network eligibleWifiNetwork() {
        if (connectivity == null) return null;
        try {
            Network network = connectivity.getActiveNetwork();
            if (network == null) return null;
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            // A VPN or multi-transport network may move packets to cellular without notice.
            boolean connectedWifi = capabilities != null
                    && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
            if (requestedSsid == null || requestedSsid.isEmpty()) {
                return connectedWifi ? network : null;
            }
            boolean hasPermission = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            boolean locationEnabled = isLocationEnabled();
            String reportedSsid = connectedWifi && hasPermission && locationEnabled
                    ? currentSsid(network, capabilities) : null;
            return WifiEligibility.permits(true, requestedSsid, connectedWifi, hasPermission,
                    locationEnabled, reportedSsid) && network.equals(connectivity.getActiveNetwork())
                    ? network : null;
        } catch (SecurityException e) {
            // Missing permission or redacted network data must never turn into a bypass.
            return null;
        }
    }

    private boolean isLocationEnabled() {
        if (location == null) return false;
        if (Build.VERSION.SDK_INT >= 28) return location.isLocationEnabled();
        return location.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || location.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    private String currentSsid(Network selectedNetwork, NetworkCapabilities capabilities) {
        // Prefer the selected network's WifiInfo when unredacted. Synchronous
        // getNetworkCapabilities() may redact location-sensitive fields even with
        // permission, so fall back to WifiManager's current Wi-Fi association.
        if (Build.VERSION.SDK_INT >= 29 && capabilities.getTransportInfo() instanceof WifiInfo) {
            String ssid = ((WifiInfo) capabilities.getTransportInfo()).getSSID();
            if (ssid != null && !WifiManager.UNKNOWN_SSID.equals(ssid)) {
                return selectedNetwork.equals(connectivity.getActiveNetwork()) ? ssid : null;
            }
        }
        // A default-network switch during the lookup invalidates the association;
        // Network.openConnection binds the selected Network rather than falling back.
        WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
        return info == null || !selectedNetwork.equals(connectivity.getActiveNetwork())
                ? null : info.getSSID();
    }
}
