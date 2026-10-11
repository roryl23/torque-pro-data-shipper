package com.roryl23.torqueshipper.delivery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public class DeliveryQueueInstrumentedTest {
    private File root;
    private File queueDirectory;
    private DeliveryQueue queue;

    @Before public void createIsolatedQueue() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        root = new File(target.getCacheDir(), "queue-test-" + UUID.randomUUID());
        assertTrue(root.mkdir());
        queueDirectory = new File(root, "delivery-queue");
        Context isolated = new ContextWrapper(target) {
            @Override public File getFilesDir() {
                return root;
            }
        };
        queue = new DeliveryQueue(isolated);
    }

    @After public void deleteIsolatedQueue() throws IOException {
        if (root != null) {
            deleteRecursively(root);
        }
    }

    @Test public void enqueueCapturesCredentialsAndBodyWithoutEndpoint() throws Exception {
        JSONObject body = new JSONObject().put("timestamp", 123).put("value", "test");
        queue.enqueue("secret-A", "X-Device-Key", body);
        File[] files = entries();
        assertEquals(1, files.length);
        JSONObject entry = read(files[0]);
        assertFalse(entry.has("endpoint"));
        assertEquals(3, entry.length());
        assertEquals("secret-A", entry.getString("apiKey"));
        assertEquals("X-Device-Key", entry.getString("apiKeyHeader"));
        assertEquals(body.toString(), entry.getString("body"));
    }

    @Test public void legacyEntriesUseSuppliedEndpointWithoutRewritingAndKeepFifo() throws Exception {
        String old = "http://old.invalid/ingest";
        String current = "https://current.invalid/collect";
        for (int i = 1; i <= 9; i++) {
            JSONObject entry = new JSONObject()
                    .put("apiKey", "secret-" + i)
                    .put("apiKeyHeader", "X-Key-" + i)
                    .put("body", "{\"sample\":" + i + "}")
                    .put("extra", "preserve-" + i);
            if (i != 3) {
                entry.put("endpoint", old);
            }
            writeEntry(i, entry);
        }
        byte[][] originals = new byte[9][];
        for (int i = 1; i <= 9; i++) {
            originals[i - 1] = Files.readAllBytes(new File(queueDirectory, filename(i)).toPath());
        }
        assertEquals(0, queue.flush(current, url -> new FakeConnection(url, 503)));
        assertEquals(9, queue.pendingCount());
        List<String> destinations = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        DeliveryQueue.Transport transport = url -> {
            // Every remaining entry, including the one being sent, retains its original
            // bytes; even the legacy destination is never read as a routing decision.
            for (File file : entries()) {
                int sequence = Integer.parseInt(file.getName().substring(0, 20));
                assertTrue(Arrays.equals(originals[sequence - 1], Files.readAllBytes(file.toPath())));
            }
            destinations.add(url.toExternalForm());
            return new FakeConnection(url, 200) {
                @Override public int getResponseCode() {
                    bodies.add(new String(written.toByteArray(), StandardCharsets.UTF_8));
                    keys.add(getRequestProperty("X-Key-" + bodies.size()));
                    return super.getResponseCode();
                }
            };
        };
        assertEquals(8, queue.flush(current, transport));
        assertEquals(1, queue.pendingCount());
        assertEquals(filename(9), entries()[0].getName());
        assertEquals(1, queue.flush(current, transport));
        assertEquals(0, queue.pendingCount());
        for (int i = 1; i <= 9; i++) {
            assertEquals(current, destinations.get(i - 1));
            assertEquals("{\"sample\":" + i + "}", bodies.get(i - 1));
            assertEquals("secret-" + i, keys.get(i - 1));
        }
    }

    @Test public void invalidCurrentEndpointDoesNotRewriteOrDeliverLegacyEntry() throws Exception {
        File file = writeEntry(1, new JSONObject().put("endpoint", "http://old.invalid")
                .put("apiKey", "secret").put("apiKeyHeader", "X-Key").put("body", "{}"));
        byte[] original = Files.readAllBytes(file.toPath());
        assertEquals(0, queue.flush("file:///unexpected", url -> {
            throw new AssertionError("Invalid endpoint cannot start a request");
        }));
        assertTrue(Arrays.equals(original, Files.readAllBytes(file.toPath())));
        assertEquals(1, queue.pendingCount());
    }

    @Test public void corruptHeadBlocksPostsWithoutLosingFiles() throws Exception {
        if (!queueDirectory.mkdir()) {
            throw new IOException("Cannot create test queue");
        }
        File first = new File(queueDirectory, filename(1));
        Files.write(first.toPath(), "not JSON".getBytes(StandardCharsets.UTF_8));
        File second = writeEntry(2, new JSONObject().put("endpoint", "http://old.invalid")
                .put("apiKey", "secret").put("apiKeyHeader", "X-Key").put("body", "{}"));
        byte[] original = Files.readAllBytes(second.toPath());
        assertEquals(0, queue.flush("http://new.invalid", url -> {
            throw new AssertionError("Corrupt FIFO head cannot be skipped");
        }));
        assertEquals(2, queue.pendingCount());
        assertTrue(Arrays.equals(original, Files.readAllBytes(second.toPath())));
        assertEquals("not JSON", new String(Files.readAllBytes(first.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void onlyExactly200AcknowledgesAndRetryUsesLatestEndpoint() throws Exception {
        JSONObject body = new JSONObject().put("sample", 7);
        queue.enqueue("secret", "X-Key", body);
        List<String> destinations = new ArrayList<>();
        assertEquals(0, queue.flush("http://first.invalid", url -> {
            destinations.add(url.toExternalForm());
            return new FakeConnection(url, 201);
        }));
        assertEquals(1, queue.pendingCount());
        assertEquals(1, queue.flush("http://second.invalid", url -> {
            destinations.add(url.toExternalForm());
            return new FakeConnection(url, 200);
        }));
        assertEquals(Arrays.asList("http://first.invalid", "http://second.invalid"), destinations);
        assertEquals(0, queue.pendingCount());
    }

    private File writeEntry(int sequence, JSONObject entry) throws IOException {
        if (!queueDirectory.isDirectory() && !queueDirectory.mkdir()) {
            throw new IOException("Cannot create test queue");
        }
        File file = new File(queueDirectory, filename(sequence));
        Files.write(file.toPath(), entry.toString().getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String filename(int sequence) {
        return String.format(java.util.Locale.ROOT, "%020d.json", sequence);
    }

    private File[] entries() {
        File[] files = queueDirectory.listFiles((dir, name) -> name.matches("[0-9]{20}\\.json"));
        if (files == null) {
            return new File[0];
        }
        Arrays.sort(files, (left, right) -> left.getName().compareTo(right.getName()));
        return files;
    }

    private static JSONObject read(File file) throws IOException {
        try {
            return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (org.json.JSONException e) {
            throw new IOException("Malformed test queue entry", e);
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        Files.deleteIfExists(file.toPath());
    }

    private static class FakeConnection extends HttpURLConnection {
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        private final int responseCode;

        FakeConnection(URL url, int responseCode) {
            super(url);
            this.responseCode = responseCode;
        }

        @Override public void connect() { }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public OutputStream getOutputStream() { return written; }
        @Override public int getResponseCode() { return responseCode; }
    }
}
