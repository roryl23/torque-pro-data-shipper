package com.roryl23.torqueshipper.delivery;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** A private, persistent, ordered outbox. Enqueue and HTTP delivery use independent locks. */
public final class DeliveryQueue {
    private static final int MAX_POSTS_PER_FLUSH = 8;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 5_000;
    private static final String DEFAULT_KEY_HEADER = "X-API-Key";
    private static final FileFilter ENTRY_FILTER = file -> {
        String name = file.getName();
        if (!file.isFile() || name.length() != 25 || !name.endsWith(".json")) {
            return false;
        }
        for (int i = 0; i < 20; i++) {
            if (name.charAt(i) < '0' || name.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    };
    private static final Comparator<File> ENTRY_ORDER = Comparator.comparing(File::getName);
    private static final Map<String, QueueState> STATES = new HashMap<>();

    private static final class QueueState {
        final Object enqueueLock = new Object();
        final Object flushLock = new Object();
        long nextSequence = -1;
        boolean parentSynced;
    }

    private final File directory;
    private final QueueState state;
    /** Selects the transport anew for each document, after the preceding HTTP request. */
    public interface Transport {
        HttpURLConnection open(URL endpoint) throws IOException;
    }

    public DeliveryQueue(Context context) {
        directory = new File(context.getFilesDir(), "delivery-queue");
        synchronized (STATES) {
            state = STATES.computeIfAbsent(directory.getAbsolutePath(), ignored -> new QueueState());
        }
    }

    /** Validates exactly the destination/credential rules used before an entry is persisted. */
    public static void validateConfiguration(String endpoint, String apiKey, String apiKeyHeader)
            throws IOException {
        checkedEndpoint(endpoint);
        checkedHeader(apiKeyHeader);
        checkedKey(apiKey);
    }

    /** Persists the document before returning. Endpoint and credentials are captured with it. */
    public void enqueue(String endpoint, String apiKey, String apiKeyHeader,
                        JSONObject document) throws IOException {
        synchronized (state.enqueueLock) {
            enqueueLocked(endpoint, apiKey, apiKeyHeader, document);
        }
    }

    private void enqueueLocked(String endpoint, String apiKey, String apiKeyHeader,
                               JSONObject document) throws IOException {
        URL url = checkedEndpoint(endpoint);
        String header = checkedHeader(apiKeyHeader);
        checkedKey(apiKey);
        if (document == null) {
            throw new IOException("A JSON document is required");
        }
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Cannot create delivery queue directory");
        }
        // Persist the queue directory's name as well as its contents. Retrying after a
        // failed parent sync is essential: creation may have succeeded despite the error.
        if (!state.parentSynced) {
            syncDirectory(directory.getParentFile());
            state.parentSynced = true;
        }
        if (state.nextSequence < 0) {
            File[] entries = entries();
            long maximum = 0;
            for (File entry : entries) {
                try {
                    maximum = Math.max(maximum, Long.parseLong(entry.getName().substring(0, 20)));
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid delivery queue sequence", e);
                }
            }
            state.nextSequence = maximum + 1;
        }
        if (state.nextSequence <= 0 || state.nextSequence == Long.MAX_VALUE) {
            throw new IOException("Delivery queue sequence exhausted");
        }
        String filename = sequenceFilename(state.nextSequence);
        File destination = new File(directory, filename);
        File temporary = new File(directory, ".pending-" + UUID.randomUUID() + ".tmp");
        try {
            JSONObject entry = new JSONObject();
            entry.put("endpoint", url.toExternalForm());
            entry.put("apiKey", apiKey);
            entry.put("apiKeyHeader", header);
            String body = document.toString();
            if (body == null) {
                throw new IOException("Cannot serialize JSON document");
            }
            entry.put("body", body);
            String serialized = entry.toString();
            if (serialized == null) {
                throw new IOException("Cannot serialize delivery queue entry");
            }
            byte[] bytes = serialized.getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(bytes);
                output.getFD().sync();
            }
            if (destination.exists() || !temporary.renameTo(destination)) {
                throw new IOException("Cannot commit delivery queue entry");
            }
            // The rename is visible now even if directory fsync fails. Never reuse this
            // sequence on an error; the caller receives an explicit uncertain-commit error.
            state.nextSequence++;
            try {
                syncDirectory(directory);
            } catch (IOException e) {
                throw new IOException("Entry queued but directory durability is uncertain", e);
            }
        } catch (JSONException e) {
            throw new IOException("Cannot serialize delivery queue entry", e);
        } finally {
            // A failed or interrupted enqueue never exposes a partial entry to flush().
            // If interrupted before rename, the .tmp is inert; later attempts use a fresh name.
            if (temporary.exists()) {
                temporary.delete();
            }
        }
    }

    /** Posts up to eight oldest entries, stopping on the first failure and retaining it for retry. */
    public int flush(Transport transport) {
        // Only a delivery worker may post/delete. Enqueue can commit snapshots while HTTP
        // waits on the network; its atomic rename makes newly listed entries immutable.
        synchronized (state.flushLock) {
            File[] queued;
            try {
                queued = entries();
            } catch (IOException e) {
                return 0;
            }
            int delivered = 0;
            for (File file : queued) {
                if (delivered == MAX_POSTS_PER_FLUSH) {
                    break;
                }
                try {
                    JSONObject entry = readEntry(file);
                    URL endpoint = checkedEndpoint(entry.getString("endpoint"));
                    String header = checkedHeader(entry.getString("apiKeyHeader"));
                    String apiKey = entry.getString("apiKey");
                    checkedKey(apiKey);
                    String body = entry.getString("body");
                    if (!post(transport, endpoint, header, apiKey, body)) {
                        break;
                    }
                    // An exit after HTTP success but before durable removal can replay the
                    // document. Never advance if unlink fails; sync the unlink before the
                    // next request to minimize replay even after a device power failure.
                    if (!file.delete()) {
                        break;
                    }
                    delivered++;
                    syncDirectory(directory);
                } catch (IOException | JSONException | RuntimeException e) {
                    // No logging: exception messages from HTTP clients can contain URL credentials.
                    // A malformed/corrupt entry must not be skipped (which would lose a snapshot).
                    break;
                }
            }
            return delivered;
        }
    }

    /** Returns -1 if the queue directory cannot be read. */
    public int pendingCount() {
        try {
            return entries().length;
        } catch (IOException e) {
            return -1;
        }
    }

    private static String sequenceFilename(long sequence) {
        char[] name = new char[25];
        name[20] = '.';
        name[21] = 'j';
        name[22] = 's';
        name[23] = 'o';
        name[24] = 'n';
        for (int i = 19; i >= 0; i--) {
            name[i] = (char) ('0' + sequence % 10);
            sequence /= 10;
        }
        return new String(name);
    }

    // App-private storage normally supports directory fsync. If a filesystem refuses it,
    // enqueue fails instead of claiming power-loss durability the OS cannot provide.
    private static void syncDirectory(File path) throws IOException {
        FileDescriptor descriptor;
        try {
            descriptor = Os.open(path.getAbsolutePath(), OsConstants.O_RDONLY, 0);
        } catch (ErrnoException e) {
            throw new IOException("Cannot open delivery queue directory for sync", e);
        }
        try {
            Os.fsync(descriptor);
        } catch (ErrnoException e) {
            throw new IOException("Cannot sync delivery queue directory", e);
        } finally {
            try {
                Os.close(descriptor);
            } catch (ErrnoException ignored) {
                // fsync has already completed (or failed); close cannot undo its result.
            }
        }
    }

    private File[] entries() throws IOException {
        if (!directory.exists()) {
            return new File[0];
        }
        File[] result = directory.listFiles(ENTRY_FILTER);
        if (result == null) {
            throw new IOException("Cannot read delivery queue directory");
        }
        Arrays.sort(result, ENTRY_ORDER);
        return result;
    }

    private static JSONObject readEntry(File file) throws IOException, JSONException {
        // Queue entries are serialized individually and renamed into place only after sync.
        byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private static boolean post(Transport transport, URL endpoint, String header, String apiKey,
                                String body) throws IOException {
        HttpURLConnection connection = transport.open(endpoint);
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty(header, apiKey);
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            int status = connection.getResponseCode();
            return status >= 200 && status < 300;
        } finally {
            connection.disconnect();
        }
    }

    private static URL checkedEndpoint(String value) throws IOException {
        if (value == null) {
            throw new IOException("An HTTP(S) endpoint is required");
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null || uri.getHost().isEmpty()
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getPort() > 65535 || uri.getPort() < -1) {
                throw new IOException("Endpoint must be an HTTP(S) URL without credentials or fragment");
            }
            return uri.toURL();
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IOException("Invalid delivery endpoint", e);
        }
    }

    private static String checkedHeader(String value) throws IOException {
        String header = value == null || value.isEmpty() ? DEFAULT_KEY_HEADER : value;
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || "!#$%&'*+-.^_`|~".indexOf(c) >= 0)) {
                throw new IOException("Invalid API key header name");
            }
        }
        // Never let the configurable credential header replace transport metadata or
        // proxy authentication. HttpURLConnection may silently rewrite these names.
        if (header.equalsIgnoreCase("Content-Type") || header.equalsIgnoreCase("Content-Length")
                || header.equalsIgnoreCase("Host") || header.equalsIgnoreCase("Connection")
                || header.equalsIgnoreCase("Transfer-Encoding")
                || header.equalsIgnoreCase("Proxy-Authorization")) {
            throw new IOException("Reserved API key header name");
        }
        return header;
    }

    private static void checkedKey(String value) throws IOException {
        if (value == null || value.isEmpty()) {
            throw new IOException("An API key is required");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                throw new IOException("Invalid API key header value");
            }
        }
    }
}
