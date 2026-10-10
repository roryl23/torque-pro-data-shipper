package com.roryl23.torqueshipper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.roryl23.torqueshipper.delivery.DeliveryQueue;
import com.roryl23.torqueshipper.delivery.WifiDeliveryGate;
import com.roryl23.torqueshipper.template.JsonTemplate;
import com.roryl23.torqueshipper.torque.TorqueReader;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class CollectionService extends Service {
    public static final String ACTION_START = "com.roryl23.torqueshipper.START";
    private static final String ACTION_STOP = "com.roryl23.torqueshipper.STOP";
    static final String PREFERENCES = "shipper_settings";
    static final String ENDPOINT = "endpoint";
    static final String API_KEY = "api_key";
    static final String API_KEY_HEADER = "api_key_header";
    static final String INTERVAL_SECONDS = "interval_seconds";
    static final String JSON_TEMPLATE = "json_template";
    static final String WIFI_ONLY = "wifi_only";
    static final String WIFI_SSID = "wifi_ssid";

    private static final String CHANNEL_ID = "torque_shipper_collection";
    private static final int NOTIFICATION_ID = 1;
    // A stopped service can have one in-flight sample while a new instance is starting.
    private static final Object SAMPLE_LOCK = new Object();

    public interface StatusListener {
        void onStatusChanged(String description, boolean running);
    }

    public final class LocalBinder extends Binder {
        CollectionService getService() {
            return CollectionService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<StatusListener> statusListeners = new ArrayList<>();
    private ScheduledExecutorService sampler;
    private ExecutorService uploader;
    private final AtomicBoolean flushRunning = new AtomicBoolean();
    private final AtomicBoolean flushRequested = new AtomicBoolean();
    // Negative: active run without a failed drain; positive: that run has retained entries.
    private final AtomicLong retryGeneration = new AtomicLong();
    private ScheduledFuture<?> periodicTask;
    private ScheduledFuture<?> retryTask;
    private TorqueReader reader;
    private DeliveryQueue queue;
    private WifiDeliveryGate deliveryGate;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile boolean running;
    private volatile long generation;
    private boolean torqueConnected;
    private int queuedCount;
    private String lastError;
    private String stoppedDescription;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        sampler = Executors.newSingleThreadScheduledExecutor(task ->
                new Thread(task, "torque-shipper-sampler"));
        uploader = Executors.newSingleThreadExecutor(task ->
                new Thread(task, "torque-shipper-upload"));
        // A reader belongs to one started collection, not to the bound UI lifecycle.
        queue = new DeliveryQueue(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopCollection();
            return START_NOT_STICKY;
        }
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;

        SharedPreferences settings = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        String endpoint = settings.getString(ENDPOINT, "");
        String key = settings.getString(API_KEY, "");
        String header = settings.getString(API_KEY_HEADER, "X-API-Key");
        int seconds = settings.getInt(INTERVAL_SECONDS, 5);
        boolean wifiOnly = settings.getBoolean(WIFI_ONLY, false);
        String wifiSsid = settings.getString(WIFI_SSID, "");
        JsonTemplate template;
        try {
            template = JsonTemplate.compile(settings.getString(JSON_TEMPLATE, ""));
        } catch (IllegalArgumentException e) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        try {
            DeliveryQueue.validateConfiguration(endpoint, key, header);
        } catch (IOException e) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (seconds < 1 || seconds > 86400) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        deliveryGate = new WifiDeliveryGate(this, wifiOnly, wifiSsid);
        long runGeneration = ++generation;
        TorqueReader activeReader = new TorqueReader(this);
        reader = activeReader;
        torqueConnected = false;
        running = true;
        retryGeneration.set(-runGeneration);
        stoppedDescription = null;
        lastError = null;
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification(getString(R.string.notification_starting)),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification(getString(R.string.notification_starting)));
        }
        publishStatus();
        watchConnectivity(runGeneration);
        sampler.execute(() -> {
            if (running && generation == runGeneration) {
                activeReader.start(() -> updateTorqueConnection(true, runGeneration),
                        () -> updateTorqueConnection(false, runGeneration));
            }
        });
        periodicTask = sampler.scheduleAtFixedRate(
                () -> tick(endpoint, key, header, template, activeReader, runGeneration),
                0, seconds, TimeUnit.SECONDS);
        retryTask = sampler.scheduleAtFixedRate(() -> {
            if (running && generation == runGeneration && retryGeneration.get() == runGeneration
                    && !flushRunning.get()) {
                requestFlush(runGeneration);
            }
        }, 5, 5, TimeUnit.SECONDS);
        return START_NOT_STICKY;
    }

    private void tick(String endpoint, String key, String header, JsonTemplate template,
                      TorqueReader activeReader, long runGeneration) {
        if (!running || generation != runGeneration) return;
        String error = null;
        try {
            synchronized (SAMPLE_LOCK) {
                if (!running || generation != runGeneration) return;
                JSONObject document = template.render(activeReader.snapshot(System.currentTimeMillis()));
                // A started tick owns its completed sample even if Stop arrived during snapshot().
                queue.enqueue(endpoint, key, header, document);
            }
        } catch (IOException | RuntimeException e) {
            // Deliberately never include URLs, HTTP exceptions' messages, or API keys in UI/notifications.
            error = e.getClass().getSimpleName();
        }
        if (!running || generation != runGeneration) return;
        requestFlush(runGeneration);
        int pending;
        try {
            pending = queue.pendingCount();
        } catch (RuntimeException e) {
            pending = -1;
            error = e.getClass().getSimpleName();
        }
        String resultError = error;
        int resultCount = pending;
        mainHandler.post(() -> {
            if (!running || generation != runGeneration) return;
            queuedCount = resultCount;
            lastError = resultError;
            publishStatus();
        });
    }

    private void requestFlush(long runGeneration) {
        if (!running || generation != runGeneration) return;
        flushRequested.set(true);
        if (flushRunning.compareAndSet(false, true)) {
            WifiDeliveryGate runGate = deliveryGate;
            uploader.execute(() -> flushQueue(runGeneration, runGate));
        }
    }

    private void markRetryNeeded(int count, long runGeneration) {
        // Compare against this run's own state so a finishing worker cannot change a newer run.
        if (count == 0) {
            retryGeneration.compareAndSet(runGeneration, -runGeneration);
        } else {
            retryGeneration.compareAndSet(-runGeneration, runGeneration);
        }
    }

    private void flushQueue(long runGeneration, WifiDeliveryGate runGate) {
        try {
            int posted;
            do {
                if (!running || generation != runGeneration) return;
                flushRequested.set(false);
                posted = queue.flush(runGate);
                int count = queue.pendingCount();
                markRetryNeeded(count, runGeneration);
                mainHandler.post(() -> {
                    if (!running || generation != runGeneration) return;
                    queuedCount = count;
                    publishStatus();
                });
            } while (posted == 8 || flushRequested.get());
        } catch (RuntimeException e) {
            int count;
            try {
                count = queue.pendingCount();
            } catch (RuntimeException unreadable) {
                count = -1;
            }
            markRetryNeeded(count, runGeneration);
            String error = e.getClass().getSimpleName();
            mainHandler.post(() -> {
                if (!running || generation != runGeneration) return;
                lastError = error;
                publishStatus();
            });
        } finally {
            flushRunning.set(false);
            // A sample could arrive as the previous upload finishes, or a new run may
            // begin before this worker completes. Hand off that request without overlap.
            if (running && flushRequested.get()) requestFlush(generation);
        }
    }

    private void watchConnectivity(long runGeneration) {
        ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
        if (connectivity == null) return;
        WifiDeliveryGate runGate = deliveryGate;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                drainIfEligible();
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                drainIfEligible();
            }

            private void drainIfEligible() {
                if (running && generation == runGeneration && runGate.isEligible()) {
                    requestFlush(runGeneration);
                }
            }
        };
        connectivity.registerDefaultNetworkCallback(networkCallback);
    }

    private void stopWatchingConnectivity() {
        ConnectivityManager.NetworkCallback callback = networkCallback;
        networkCallback = null;
        if (callback != null) {
            getSystemService(ConnectivityManager.class).unregisterNetworkCallback(callback);
        }
    }

    private void updateTorqueConnection(boolean connected, long runGeneration) {
        mainHandler.post(() -> {
            if (!running || generation != runGeneration) return;
            torqueConnected = connected;
            publishStatus();
        });
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, CollectionService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_status)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setContentIntent(openPending)
                .setOngoing(true)
                .addAction(R.drawable.ic_status, getString(R.string.notification_stop), stopPending)
                .build();
    }

    private String description() {
        if (!running) {
            return stoppedDescription != null ? stoppedDescription : getString(R.string.status_stopped);
        }
        String state = getString(torqueConnected ? R.string.status_connected : R.string.status_disconnected);
        String count = queuedCount < 0 ? "?" : Integer.toString(queuedCount);
        String description = getString(R.string.status_running, state, count);
        if (lastError != null) description += "\n" + getString(R.string.status_error, lastError);
        return description;
    }

    private void publishStatus() {
        if (running) {
            String state = getString(torqueConnected ? R.string.status_connected : R.string.status_disconnected);
            String count = queuedCount < 0 ? "?" : Integer.toString(queuedCount);
            String summary = getString(R.string.notification_running, state, count);
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(summary));
        }
        String description = description();
        for (StatusListener listener : new ArrayList<>(statusListeners)) {
            listener.onStatusChanged(description, running);
        }
    }

    public void addStatusListener(StatusListener listener) {
        statusListeners.add(listener);
        listener.onStatusChanged(description(), running);
    }

    public void removeStatusListener(StatusListener listener) {
        statusListeners.remove(listener);
    }

    public void stopCollection() {
        if (running) {
            running = false;
            ++generation;
            retryGeneration.set(0);
            flushRequested.set(false);
            stopWatchingConnectivity();
            if (periodicTask != null) periodicTask.cancel(false);
            if (retryTask != null) retryTask.cancel(false);
            if (reader != null) reader.close();
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
        publishStatus();
        stopSelf();
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        stoppedDescription = getString(R.string.status_time_limit);
        stopCollection();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        running = false;
        ++generation;
        retryGeneration.set(0);
        stopWatchingConnectivity();
        flushRequested.set(false);
        if (periodicTask != null) periodicTask.cancel(false);
        if (retryTask != null) retryTask.cancel(false);
        if (reader != null) reader.close();
        publishStatus();
        // Let a snapshot already being written finish its atomic enqueue.
        sampler.shutdown();
        uploader.shutdownNow();
        super.onDestroy();
    }
}
