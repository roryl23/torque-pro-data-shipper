package com.roryl23.torqueshipper.torque;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.prowl.torque.remote.ITorqueService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A service-lifetime Torque binding. Call snapshot on a single background collection thread. */
public final class TorqueReader {
    private static final long ENUMERATION_INTERVAL_MS = 60_000L;
    private static final int BATCH_SIZE = 64;
    private static final String TORQUE_PACKAGE = "org.prowl.torque";
    private static final String TORQUE_SERVICE = "org.prowl.torque.remote.TorqueService";

    private final Context context;
    private final Map<String, JSONObject> metadata = new LinkedHashMap<>();
    private Set<String> activeIds;
    private Set<String> ecuSupportedIds;
    private JSONObject cachedProfile;
    private long lastProfileRead;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (bindingLock) {
                if (closed || bindingInvalidated) return;
                lastEnumeration = 0;
                activeIds = null;
                ecuSupportedIds = null;
                cachedProfile = null;
                lastProfileRead = 0;
                service = ITorqueService.Stub.asInterface(binder);
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            synchronized (bindingLock) { service = null; }
            notifyConnection(false);
        }

        @Override public void onBindingDied(ComponentName name) {
            invalidateBinding();
        }

        @Override public void onNullBinding(ComponentName name) {
            invalidateBinding();
        }
    };

    private final Object bindingLock = new Object();
    private boolean bindingInProgress;
    private boolean bindingInvalidated;
    private boolean releasing;
    private volatile ITorqueService service;
    private volatile boolean closed;
    private volatile boolean started;
    private boolean bound;
    private boolean connected;
    private Runnable onConnected;
    private Runnable onDisconnected;
    private volatile long lastEnumeration;

    public TorqueReader(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Starts a non-creating binding: Torque must already be running. Callbacks report ECU state. */
    public void start(Runnable connected, Runnable disconnected) {
        synchronized (bindingLock) {
            if (closed) throw new IllegalStateException("TorqueReader is closed");
            onConnected = connected;
            onDisconnected = disconnected;
            started = true;
        }
        bindIfNeeded();
    }

    private void bindIfNeeded() {
        synchronized (bindingLock) {
            if (!started || closed || bound || bindingInProgress || releasing) return;
            bindingInProgress = true;
            bindingInvalidated = false;
        }
        Intent intent = new Intent().setClassName(TORQUE_PACKAGE, TORQUE_SERVICE);
        boolean success;
        try {
            // Flags 0 match Torque's official plugin; don't start Torque behind the user's back.
            success = context.bindService(intent, connection, 0);
        } catch (SecurityException | IllegalArgumentException | IllegalStateException exception) {
            success = false;
        }
        boolean release;
        synchronized (bindingLock) {
            bindingInProgress = false;
            release = success && (closed || !started || bindingInvalidated);
            bound = success && !release;
            if (release) {
                service = null;
                releasing = true;
            }
        }
        if (release) unbindSafely();
    }

    public void close() {
        boolean release;
        synchronized (bindingLock) {
            if (closed) return;
            closed = true;
            started = false;
            service = null;
            release = bound;
            bound = false;
            if (release) releasing = true;
        }
        if (release) unbindSafely();
        notifyConnection(false);
    }

    private void invalidateBinding() {
        boolean release;
        synchronized (bindingLock) {
            service = null;
            bindingInvalidated = true;
            release = bound;
            bound = false;
            if (release) releasing = true;
        }
        // A binding in progress releases itself when bindService returns.
        if (release) unbindSafely();
        notifyConnection(false);
    }

    private void unbindSafely() {
        try {
            context.unbindService(connection);
        } catch (IllegalArgumentException ignored) {
            // A dead or null binding may already have been removed by Android.
        } finally {
            synchronized (bindingLock) { releasing = false; }
        }
    }

    private synchronized void notifyConnection(boolean state) {
        if (closed) state = false;
        if (connected == state) return;
        connected = state;
        Runnable callback = state ? onConnected : onDisconnected;
        if (callback != null) callback.run();
    }

    /** Always returns a fresh document, even when Torque is absent or its ECU is disconnected. */
    public JSONObject snapshot(long timestampMs) {
        JSONObject document = new JSONObject();
        put(document, "timestampMs", timestampMs);
        JSONArray pids = new JSONArray();
        put(document, "pids", pids);
        JSONObject dtcs = new JSONObject();
        put(document, "dtcs", dtcs);
        if (started && !closed) bindIfNeeded();
        ITorqueService remote = closed ? null : service;
        put(document, "vehicleProfile", remote == null ? statusObject("torque_unavailable")
                : vehicleProfile(remote, timestampMs));
        put(document, "adapter", remote == null ? statusObject("torque_unavailable")
                : adapterDetails(remote));
        String status = remote == null ? "torque_unavailable" : "ecu_disconnected";
        if (remote != null) {
            try {
                if (remote.isConnectedToECU()) status = "connected";
            } catch (RemoteException | RuntimeException exception) {
                status = "remote_error";
            }
        }
        boolean active = "connected".equals(status);
        notifyConnection(active);
        put(document, "connected", active);
        put(document, "status", status);
        if (active) {
            try {
                refreshPids(remote, timestampMs);
                samplePids(remote, pids);
            } catch (RemoteException | RuntimeException exception) {
                // Retain identities and metadata, but do not present stale sensor readings.
                pids = unavailablePids("remote_error");
                put(document, "pids", pids);
                put(document, "status", "remote_error");
            }
        } else {
            pids = unavailablePids(status);
            put(document, "pids", pids);
        }
        for (int mode : new int[]{0x03, 0x07, 0x0a}) {
            String category = mode == 0x03 ? "stored" : mode == 0x07 ? "pending" : "permanent";
            JSONObject result = active ? readDtcs(remote, mode) : emptyDtcs(status);
            put(dtcs, category, result);
        }
        return document;
    }

    private void refreshPids(ITorqueService remote, long timestampMs) throws RemoteException {
        if (lastEnumeration > 0 && timestampMs >= lastEnumeration
                && timestampMs - lastEnumeration < ENUMERATION_INTERVAL_MS) return;
        // Newer API includes detected sensors not exposed by the old listAllPIDs method.
        String[] all;
        try {
            all = remote.listAllPIDsIncludingDetectedPIDs();
        } catch (RemoteException | RuntimeException unsupported) {
            all = remote.listAllPIDs();
        }
        if (all == null) all = remote.listAllPIDs();
        String[] active = remote.listActivePIDs();
        String[] supported;
        try {
            supported = remote.listECUSupportedPIDs();
        } catch (RemoteException | RuntimeException unsupportedApi) {
            supported = null;
        }
        activeIds = active == null ? null : new LinkedHashSet<>(Arrays.asList(active));
        ecuSupportedIds = supported == null ? null : new LinkedHashSet<>(Arrays.asList(supported));
        Set<String> ids = new LinkedHashSet<>();
        if (all != null) ids.addAll(Arrays.asList(all));
        if (active != null) ids.addAll(Arrays.asList(active));
        ids.remove(null);
        ids.remove("");
        // A null enumeration must not erase the last-known catalog during a transient failure.
        if (all == null && active == null) return;
        Map<String, JSONObject> next = new LinkedHashMap<>();
        for (String id : ids) next.put(id, metadata.get(id));
        metadata.clear();
        metadata.putAll(next);
        List<String> list = new ArrayList<>(metadata.keySet());
        for (int start = 0; start < list.size(); start += BATCH_SIZE) {
            String[] batch = list.subList(start, Math.min(start + BATCH_SIZE, list.size()))
                    .toArray(new String[0]);
            String[] descriptions;
            try {
                descriptions = remote.getPIDInformation(batch);
            } catch (RemoteException | RuntimeException exception) {
                descriptions = null;
            }
            for (int i = 0; i < batch.length; i++) {
                if (descriptions != null && i < descriptions.length && descriptions[i] != null) {
                    metadata.put(batch[i], parseMetadata(descriptions[i]));
                }
            }
        }
        lastEnumeration = timestampMs;
    }

    static JSONObject parseMetadata(String csv) {
        JSONObject details = new JSONObject();
        put(details, "raw", csv);
        if (csv == null) return details;
        // Torque documents six unescaped comma-separated fields. Preserve the original
        // when names contain commas instead of guessing an incorrect field alignment.
        String[] fields = csv.split(",", -1);
        if (fields.length != 6) return details;
        put(details, "name", fields[0]);
        put(details, "shortName", fields[1]);
        put(details, "unit", fields[2]);
        String[] keys = {"max", "min", "scale"};
        for (int i = 0; i < keys.length; i++) {
            try {
                double number = Double.parseDouble(fields[i + 3].trim());
                if (!Double.isNaN(number) && !Double.isInfinite(number)) put(details, keys[i], number);
            } catch (NumberFormatException ignored) { }
        }
        return details;
    }

    private void samplePids(ITorqueService remote, JSONArray pids) throws RemoteException {
        List<String> ids = new ArrayList<>(metadata.keySet());
        for (int start = 0; start < ids.size(); start += BATCH_SIZE) {
            String[] batch = ids.subList(start, Math.min(start + BATCH_SIZE, ids.size()))
                    .toArray(new String[0]);
            double[] values;
            long[] updates;
            try {
                values = remote.getPIDValuesAsDouble(batch);
                updates = remote.getPIDUpdateTime(batch);
            } catch (RemoteException | RuntimeException exception) {
                for (String id : batch) pids.put(unavailablePid(id, "remote_error"));
                continue;
            }
            for (int i = 0; i < batch.length; i++) {
                JSONObject reading = unavailablePid(batch[i], "unavailable");
                if (values != null && updates != null && i < values.length && i < updates.length
                        && updates[i] > 0 && !Double.isNaN(values[i]) && !Double.isInfinite(values[i])) {
                    put(reading, "status", "available");
                    put(reading, "value", values[i]);
                    put(reading, "updateTimeMs", updates[i]);
                }
                pids.put(reading);
            }
        }
    }

    private JSONArray unavailablePids(String status) {
        JSONArray readings = new JSONArray();
        for (String id : metadata.keySet()) readings.put(unavailablePid(id, status));
        return readings;
    }

    private JSONObject unavailablePid(String id, String status) {
        JSONObject reading = new JSONObject();
        put(reading, "id", id);
        put(reading, "status", status);
        if (activeIds != null) put(reading, "active", activeIds.contains(id));
        if (ecuSupportedIds != null) put(reading, "ecuSupported", ecuSupportedIds.contains(id));
        JSONObject info = metadata.get(id);
        if (info != null) put(reading, "metadata", info);
        return reading;
    }

    private JSONObject vehicleProfile(ITorqueService remote, long timestampMs) {
        if (cachedProfile != null && timestampMs >= lastProfileRead
                && timestampMs - lastProfileRead < ENUMERATION_INTERVAL_MS) return cachedProfile;
        JSONObject result;
        try {
            String[] values = remote.getVehicleProfileInformation();
            if (values == null || values.length == 0) {
                result = statusObject("unavailable");
            } else {
                result = statusObject("available");
                JSONArray raw = new JSONArray();
                for (String value : values) raw.put(value);
                put(result, "raw", raw);
                JSONObject fields = new JSONObject();
                String[] names = {"name", "engineDisplacementLiters", "weightKilograms",
                        "fuelType", "boostAdjustment", "maxRpm",
                        "volumetricEfficiencyPercent", "accumulatedDistance"};
                for (int i = 0; i < values.length && i < names.length; i++) {
                    if (values[i] != null) put(fields, names[i], values[i]);
                }
                put(result, "fields", fields);
            }
        } catch (RemoteException | RuntimeException exception) {
            result = statusObject("remote_error");
        }
        cachedProfile = result;
        lastProfileRead = timestampMs;
        return result;
    }

    private static JSONObject adapterDetails(ITorqueService remote) {
        JSONObject details = statusObject("available");
        try { putMetric(details, "dataErrorCount", remote.getDataErrorCount()); }
        catch (RemoteException | RuntimeException exception) { putUnavailable(details, "dataErrorCount"); }
        try {
            double speed = remote.getPIDReadSpeed();
            if (Double.isNaN(speed) || Double.isInfinite(speed)) putUnavailable(details, "pidReadSpeed");
            else putMetric(details, "pidReadSpeed", speed);
        } catch (RemoteException | RuntimeException exception) { putUnavailable(details, "pidReadSpeed"); }
        try { putMetric(details, "configuredSpeed", remote.getConfiguredSpeed()); }
        catch (RemoteException | RuntimeException exception) { putUnavailable(details, "configuredSpeed"); }
        try { putMetric(details, "protocolNumber", remote.getProtocolNumber()); }
        catch (RemoteException | RuntimeException exception) { putUnavailable(details, "protocolNumber"); }
        try {
            String name = remote.getProtocolName();
            if (name == null) putUnavailable(details, "protocolName");
            else putMetric(details, "protocolName", name);
        } catch (RemoteException | RuntimeException exception) { putUnavailable(details, "protocolName"); }
        try { putMetric(details, "fileLoggingEnabled", remote.isFileLoggingEnabled()); }
        catch (RemoteException | RuntimeException exception) { putUnavailable(details, "fileLoggingEnabled"); }
        try { putMetric(details, "webLoggingEnabled", remote.isWebLoggingEnabled()); }
        catch (RemoteException | RuntimeException exception) { putUnavailable(details, "webLoggingEnabled"); }
        try { putMetric(details, "numberOfLoggedItems", remote.getNumberOfLoggedItems()); }
        catch (RemoteException | RuntimeException exception) { putUnavailable(details, "numberOfLoggedItems"); }
        return details;
    }

    private static JSONObject statusObject(String status) {
        JSONObject result = new JSONObject();
        put(result, "status", status);
        return result;
    }

    private static void putMetric(JSONObject adapter, String key, Object value) {
        JSONObject metric = statusObject("available");
        put(metric, "value", value);
        put(adapter, key, metric);
    }

    private static void putUnavailable(JSONObject adapter, String key) {
        put(adapter, key, statusObject("unavailable"));
    }

    private static JSONObject readDtcs(ITorqueService remote, int mode) {
        try {
            String[] raw = remote.sendCommandGetResponse(null,
                    String.format(java.util.Locale.ROOT, "%02X", mode));
            DtcParser.Result parsed = DtcParser.parse(raw, mode);
            JSONObject result = emptyDtcs(parsed.ambiguous
                    ? (parsed.hasResponse ? "partial" : "unavailable")
                    : parsed.hasResponse ? "available" : "unavailable");
            JSONArray originals = new JSONArray();
            if (raw != null) for (String response : raw) originals.put(response);
            put(result, "rawResponses", originals);
            JSONArray decoded = new JSONArray();
            for (String code : parsed.codes) decoded.put(code);
            put(result, "codes", decoded);
            return result;
        } catch (RemoteException | RuntimeException exception) {
            return emptyDtcs("remote_error");
        }
    }

    private static JSONObject emptyDtcs(String status) {
        JSONObject result = new JSONObject();
        put(result, "status", status);
        put(result, "rawResponses", new JSONArray());
        put(result, "codes", new JSONArray());
        return result;
    }

    private static void put(JSONObject target, String key, Object value) {
        try {
            target.put(key, value);
        } catch (JSONException exception) {
            throw new IllegalStateException("Could not construct snapshot", exception);
        }
    }
}
