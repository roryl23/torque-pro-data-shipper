package com.roryl23.torqueshipper.template;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class JsonTemplateTest {
    @Test public void blankTemplateReturnsOriginalDocument() throws Exception {
        JSONObject document = new JSONObject().put("timestampMs", 42);
        assertSame(document, JsonTemplate.compile(null).render(document));
        assertSame(document, JsonTemplate.compile(" \n\t ").render(document));
    }

    @Test public void rootMetadataKeepsTypesAndEmbedsUnchangedDocument() throws Exception {
        JsonTemplate template = JsonTemplate.compile("{\"fleet\":\"north\",\"count\":3,"
                + "\"ratio\":1.25,\"enabled\":true,\"unknown\":null,"
                + "\"labels\":[\"blue\",false,2],\"document\":\"{{document}}\"}");
        JSONObject generated = new JSONObject().put("timestampMs", 42)
                .put("pids", new JSONArray().put(new JSONObject().put("id", "rpm")));

        JSONObject rendered = template.render(generated);

        assertNotSame(generated, rendered);
        assertSame(generated, rendered.getJSONObject("document"));
        assertEquals(42, rendered.getJSONObject("document").getInt("timestampMs"));
        assertEquals("north", rendered.getString("fleet"));
        assertEquals(3, rendered.getInt("count"));
        assertEquals(1.25, rendered.getDouble("ratio"), 0);
        assertTrue(rendered.getBoolean("enabled"));
        assertTrue(rendered.isNull("unknown"));
        JSONArray labels = rendered.getJSONArray("labels");
        assertEquals("blue", labels.getString(0));
        assertFalse(labels.getBoolean(1));
        assertEquals(2, labels.getInt(2));
        assertEquals(2, generated.length());
        assertFalse(generated.has("fleet"));
    }

    @Test public void nestedArrayPlaceholderAndMetadataAreIsolatedAcrossTicks() throws Exception {
        JsonTemplate template = JsonTemplate.compile("{\"meta\":{\"fleet\":\"north\","
                + "\"tags\":[{\"name\":\"static\"},null]},"
                + "\"payload\":[false,{\"nested\":[\"{{document}}\",7]}]}");
        JSONObject firstDocument = new JSONObject().put("sample", 1);
        JSONObject secondDocument = new JSONObject().put("sample", 2);
        JSONObject first = template.render(firstDocument);
        first.getJSONObject("meta").getJSONArray("tags").getJSONObject(0).put("name", "changed");
        first.getJSONArray("payload").getJSONObject(1).getJSONArray("nested").put("extra");

        JSONObject second = template.render(secondDocument);

        assertNotSame(first, second);
        assertNotSame(first.getJSONObject("meta"), second.getJSONObject("meta"));
        assertEquals("static", second.getJSONObject("meta").getJSONArray("tags")
                .getJSONObject(0).getString("name"));
        assertTrue(second.getJSONObject("meta").getJSONArray("tags").isNull(1));
        assertEquals(2, second.getJSONArray("payload").getJSONObject(1)
                .getJSONArray("nested").length());
        assertSame(firstDocument, first.getJSONArray("payload").getJSONObject(1)
                .getJSONArray("nested").getJSONObject(0));
        assertSame(secondDocument, second.getJSONArray("payload").getJSONObject(1)
                .getJSONArray("nested").getJSONObject(0));
        assertEquals(1, firstDocument.getInt("sample"));
        assertEquals(2, secondDocument.getInt("sample"));
    }

    @Test public void escapedPlaceholderStringIsRecognizedButKeysAndSubstringsAreNot() throws Exception {
        JsonTemplate template = JsonTemplate.compile("{\"{{document}}\":\"literal {{document}}\","
                + "\"data\":\"{{docu\\u006dent}}\"}");
        JSONObject document = new JSONObject().put("value", "ok");
        JSONObject rendered = template.render(document);
        assertEquals("literal {{document}}", rendered.getString("{{document}}"));
        assertSame(document, rendered.getJSONObject("data"));
    }

    @Test public void invalidJsonAndInvalidPlaceholderCountAreRejected() {
        assertInvalid("{", "Invalid JSON");
        assertInvalid("{\"payload\":\"{{document}}\",}", "Invalid JSON");
        assertInvalid("{payload:\"{{document}}\"}", "Invalid JSON");
        assertInvalid("{\"payload\":\"{{document}}\"} trailing", "Invalid JSON");
        assertInvalid("{\"payload\":\"{{document}}\",\"payload\":0}", "Duplicate object key");
        assertInvalid("[\"{{document}}\"]", "must be an object");
        assertInvalid("null", "must be an object");
        assertInvalid("{\"payload\":\"not a placeholder\"}", "exactly one");
        assertInvalid("{\"first\":\"{{document}}\",\"nested\":[{\"second\":\"{{document}}\"}]}",
                "multiple");
    }

    private static void assertInvalid(String text, String message) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> JsonTemplate.compile(text));
        assertTrue(exception.getMessage(), exception.getMessage().contains(message));
    }
}
