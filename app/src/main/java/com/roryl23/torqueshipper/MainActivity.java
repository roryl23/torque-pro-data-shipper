package com.roryl23.torqueshipper;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Switch;
import android.widget.Toast;

import com.roryl23.torqueshipper.delivery.DeliveryQueue;
import com.roryl23.torqueshipper.template.JsonTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

public final class MainActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 1;
    private static final int WIFI_LOCATION_PERMISSION_REQUEST = 2;
    private static final String WIFI_ONLY = "wifi_only";
    private static final String WIFI_SSID = "wifi_ssid";

    private EditText endpoint;
    private EditText apiKey;
    private EditText apiKeyHeader;
    private EditText intervalSeconds;
    private EditText jsonTemplate;
    private Switch wifiOnly;
    private EditText wifiSsid;
    private Button useConnectedWifi;
    private boolean selectingConnectedWifi;
    private TextView status;
    private TextView httpWarning;
    private Button startButton;
    private Button stopButton;
    private CollectionService service;
    private boolean bound;

    private final CollectionService.StatusListener statusListener = (description, running) -> {
        status.setText(description);
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!bound) return;
            service = ((CollectionService.LocalBinder) binder).getService();
            service.addStatusListener(statusListener);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // The old binding cannot be used to observe a subsequently restarted service.
            if (bound) {
                unbindService(serviceConnection);
                bound = false;
            }
            service = null;
            statusListener.onStatusChanged(getString(R.string.status_stopped), false);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        root.requestApplyInsets();
        endpoint = findViewById(R.id.endpoint);
        apiKey = findViewById(R.id.api_key);
        apiKeyHeader = findViewById(R.id.api_key_header);
        intervalSeconds = findViewById(R.id.interval_seconds);
        jsonTemplate = findViewById(R.id.json_template);
        wifiOnly = findViewById(R.id.wifi_only);
        wifiSsid = findViewById(R.id.wifi_ssid);
        useConnectedWifi = findViewById(R.id.use_connected_wifi);
        httpWarning = findViewById(R.id.http_warning);
        status = findViewById(R.id.status);
        startButton = findViewById(R.id.start);
        stopButton = findViewById(R.id.stop);

        SharedPreferences settings = getSharedPreferences(CollectionService.PREFERENCES, MODE_PRIVATE);
        endpoint.setText(settings.getString(CollectionService.ENDPOINT, ""));
        apiKey.setText(settings.getString(CollectionService.API_KEY, ""));
        apiKeyHeader.setText(settings.getString(CollectionService.API_KEY_HEADER, "X-API-Key"));
        intervalSeconds.setText(String.valueOf(settings.getInt(CollectionService.INTERVAL_SECONDS, 5)));
        wifiOnly.setChecked(settings.getBoolean(WIFI_ONLY, false));
        wifiSsid.setText(settings.getString(WIFI_SSID, ""));
        wifiOnly.setOnCheckedChangeListener((button, checked) -> {
            wifiSsid.setEnabled(checked);
            useConnectedWifi.setEnabled(checked);
        });
        wifiSsid.setEnabled(wifiOnly.isChecked());
        useConnectedWifi.setEnabled(wifiOnly.isChecked());
        useConnectedWifi.setOnClickListener(view -> selectConnectedWifi());
        jsonTemplate.setText(settings.getString(CollectionService.JSON_TEMPLATE, ""));
        endpoint.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                httpWarning.setVisibility(isCleartextUrl(s.toString().trim()) ? View.VISIBLE : View.GONE);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        httpWarning.setVisibility(isCleartextUrl(endpoint.getText().toString().trim())
                ? View.VISIBLE : View.GONE);

        findViewById(R.id.save).setOnClickListener(view -> saveSettings(true));
        startButton.setOnClickListener(view -> startCollection());
        stopButton.setOnClickListener(view -> stopCollection());
        statusListener.onStatusChanged(getString(R.string.status_stopped), false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // A zero-flag bind connects only if the explicitly started service already exists.
        if (!bindToRunningService(0)) {
            statusListener.onStatusChanged(getString(R.string.status_stopped), false);
        }
    }

    @Override
    protected void onStop() {
        unbindFromService();
        super.onStop();
    }

    private void unbindFromService() {
        if (bound) {
            if (service != null) service.removeStatusListener(statusListener);
            unbindService(serviceConnection);
            bound = false;
            service = null;
        }
    }

    private boolean bindToRunningService(int flags) {
        if (!bound) {
            bound = bindService(new Intent(this, CollectionService.class), serviceConnection, flags);
        }
        return bound;
    }

    private void selectConnectedWifi() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestWifiLocationPermission(true);
            return;
        }
        if (!isLocationEnabled()) {
            Toast.makeText(this, R.string.wifi_location_disabled, Toast.LENGTH_LONG).show();
            return;
        }
        ConnectivityManager connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
        if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            Toast.makeText(this, R.string.wifi_not_connected, Toast.LENGTH_LONG).show();
            return;
        }
        String ssid;
        if (Build.VERSION.SDK_INT >= 31) {
            Object transport = capabilities.getTransportInfo();
            ssid = transport instanceof WifiInfo ? ((WifiInfo) transport).getSSID() : null;
        } else {
            ssid = null;
        }
        if (isUnavailableSsid(ssid)) {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
            ssid = info == null ? null : info.getSSID();
        }
        NetworkCapabilities current = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
        if (current == null || !current.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            Toast.makeText(this, R.string.wifi_not_connected, Toast.LENGTH_LONG).show();
            return;
        }
        if (isUnavailableSsid(ssid)) {
            Toast.makeText(this, R.string.wifi_ssid_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        // Android wraps reported SSIDs in quotes; manually entered names are kept as typed.
        if (ssid.length() >= 2 && ssid.charAt(0) == '"' && ssid.charAt(ssid.length() - 1) == '"') {
            ssid = ssid.substring(1, ssid.length() - 1);
        }
        if (ssid.isEmpty()) {
            Toast.makeText(this, R.string.wifi_ssid_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        wifiSsid.setText(ssid);
    }

    private static boolean isUnavailableSsid(String ssid) {
        return ssid == null || ssid.isEmpty() || WifiManager.UNKNOWN_SSID.equals(ssid)
                || "<unknown ssid>".equalsIgnoreCase(ssid);
    }

    private boolean isLocationEnabled() {
        LocationManager location = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (location == null) return false;
        if (Build.VERSION.SDK_INT >= 28) return location.isLocationEnabled();
        return location.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || location.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    private boolean needsWifiLocationPermission() {
        return wifiOnly.isChecked() && !wifiSsid.getText().toString().isEmpty()
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED;
    }

    private void requestWifiLocationPermission() {
        requestWifiLocationPermission(false);
    }

    private void requestWifiLocationPermission(boolean selectingWifi) {
        selectingConnectedWifi = selectingWifi;
        requestPermissions(new String[]{
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
        }, WIFI_LOCATION_PERMISSION_REQUEST);
    }

    private boolean saveSettings(boolean showConfirmation) {
        String url = endpoint.getText().toString().trim();
        String key = apiKey.getText().toString();
        String header = apiKeyHeader.getText().toString().trim();
        String secondsText = intervalSeconds.getText().toString().trim();
        String templateText = jsonTemplate.getText().toString();
        String selectedWifiSsid = wifiSsid.getText().toString();
        try {
            URI parsed = new URI(url);
            if (!("https".equalsIgnoreCase(parsed.getScheme())
                    || "http".equalsIgnoreCase(parsed.getScheme()))
                    || parsed.getHost() == null || parsed.getHost().isEmpty()
                    || parsed.getRawUserInfo() != null || parsed.getRawFragment() != null
                    || parsed.getPort() > 65535) {
                endpoint.setError(getString(R.string.invalid_endpoint));
                return false;
            }
        } catch (URISyntaxException e) {
            endpoint.setError(getString(R.string.invalid_endpoint));
            return false;
        }
        if (key.isEmpty() || !hasPrintableCharacters(key)) {
            apiKey.setError(getString(R.string.invalid_api_key));
            return false;
        }
        if (!header.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
            apiKeyHeader.setError(getString(R.string.invalid_header));
            return false;
        }
        try {
            DeliveryQueue.validateConfiguration(url, key, header);
        } catch (IOException e) {
            Toast.makeText(this, R.string.invalid_configuration, Toast.LENGTH_SHORT).show();
            return false;
        }
        int seconds;
        try {
            seconds = Integer.parseInt(secondsText);
        } catch (NumberFormatException e) {
            intervalSeconds.setError(getString(R.string.invalid_interval));
            return false;
        }
        if (seconds < 1 || seconds > 86400) {
            intervalSeconds.setError(getString(R.string.invalid_interval));
            return false;
        }
        try {
            JsonTemplate.compile(templateText);
        } catch (IllegalArgumentException e) {
            jsonTemplate.setError(getString(R.string.invalid_json_template, e.getMessage()));
            return false;
        }
        boolean saved = getSharedPreferences(CollectionService.PREFERENCES, MODE_PRIVATE).edit()
                .putString(CollectionService.ENDPOINT, url)
                .putString(CollectionService.API_KEY, key)
                .putString(CollectionService.API_KEY_HEADER, header)
                .putInt(CollectionService.INTERVAL_SECONDS, seconds)
                .putString(CollectionService.JSON_TEMPLATE, templateText)
                .putBoolean(WIFI_ONLY, wifiOnly.isChecked())
                .putString(WIFI_SSID, selectedWifiSsid)
                .commit();
        if (!saved) {
            Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_SHORT).show();
            return false;
        }
        if (showConfirmation) Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        if (needsWifiLocationPermission()) requestWifiLocationPermission();
        return true;
    }

    private static boolean hasPrintableCharacters(String value) {
        for (int i = 0; i < value.length(); ++i) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7e) return false;
        }
        return true;
    }

    private static boolean isCleartextUrl(String url) {
        return url.regionMatches(true, 0, "http://", 0, 7);
    }

    private void startCollection() {
        if (!saveSettings(false)) return;
        if (needsWifiLocationPermission()) {
            Toast.makeText(this, R.string.wifi_start_permission, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent startIntent = new Intent(this, CollectionService.class).setAction(CollectionService.ACTION_START);
            startForegroundService(startIntent);
            statusListener.onStatusChanged(getString(R.string.status_starting), true);
            bindToRunningService(Context.BIND_AUTO_CREATE);
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
            }
        } catch (RuntimeException e) {
            // The class name is safe to display: a platform exception message may contain app-specific data.
            statusListener.onStatusChanged(getString(R.string.start_failed, e.getClass().getSimpleName()), false);
        }
    }

    private void stopCollection() {
        if (service != null) {
            service.stopCollection();
        } else {
            stopService(new Intent(this, CollectionService.class));
        }
        unbindFromService();
        statusListener.onStatusChanged(getString(R.string.status_stopped), false);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST
                && (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, R.string.notifications_denied, Toast.LENGTH_LONG).show();
        } else if (requestCode == WIFI_LOCATION_PERMISSION_REQUEST) {
            boolean granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                Toast.makeText(this, R.string.wifi_location_permission_denied, Toast.LENGTH_LONG).show();
            } else if (selectingConnectedWifi) {
                selectConnectedWifi();
            }
            selectingConnectedWifi = false;
        }
    }
}
