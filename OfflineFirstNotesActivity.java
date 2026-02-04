package com.example.offlinefirst;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class OfflineFirstNotesActivity extends AppCompatActivity {
    private NotesRepository repository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repository = RepositoryProvider.get(this);
        SyncScheduler.schedulePeriodic(this);
    }

    public void addNote(String text) {
        if (TextUtils.isEmpty(text)) {
            return;
        }
        repository.add(text);
        SyncScheduler.runOneTime(this);
    }
}

final class RepositoryProvider {
    static final String BASE_URL = "https://your-domain.com/api.php";

    static NotesRepository get(Context context) {
        return new NotesRepository(
                new LocalDataSource(context),
                new RemoteDataSource(BASE_URL)
        );
    }

    private RepositoryProvider() {
    }
}

final class SyncScheduler {
    private static final String UNIQUE_NAME = "notes_sync";

    static void schedulePeriodic(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                SyncWorker.class,
                15,
                TimeUnit.MINUTES
        ).setConstraints(constraints).build();

        WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    static void runOneTime(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(context).enqueue(request);
    }

    private SyncScheduler() {
    }
}

class SyncWorker extends Worker {
    SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            NotesRepository repository = RepositoryProvider.get(getApplicationContext());
            repository.sync();
            return Result.success();
        } catch (Exception error) {
            Log.w("SyncWorker", "Sync failed", error);
            return Result.retry();
        }
    }
}

final class NotesRepository {
    private final LocalDataSource local;
    private final RemoteDataSource remote;

    NotesRepository(LocalDataSource local, RemoteDataSource remote) {
        this.local = local;
        this.remote = remote;
    }

    void add(String text) {
        Note note = new Note(
                "local_" + UUID.randomUUID().toString(),
                text,
                System.currentTimeMillis(),
                false
        );
        local.addItem(note);
        local.addPending(note);
    }

    void sync() throws IOException {
        List<Note> pending = local.getPending();
        if (!pending.isEmpty()) {
            List<Note> saved = remote.pushItems(pending);
            local.markSynced(saved);
            local.setPending(new ArrayList<>());
            mergeRemote(saved);
        }

        List<Note> remoteItems = remote.fetchItems();
        mergeRemote(remoteItems);
    }

    private void mergeRemote(List<Note> remoteItems) {
        Map<String, Note> byId = new HashMap<>();
        for (Note item : local.getItems()) {
            byId.put(item.id, item);
        }
        for (Note item : remoteItems) {
            byId.put(item.id, new Note(item.id, item.text, item.createdAt, true));
        }
        List<Note> merged = new ArrayList<>(byId.values());
        Collections.sort(merged, new Comparator<Note>() {
            @Override
            public int compare(Note a, Note b) {
                return Long.compare(b.createdAt, a.createdAt);
            }
        });
        local.setItems(merged);
    }
}

final class LocalDataSource {
    private static final String PREFS = "offline_notes";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_PENDING = "pending";

    private final SharedPreferences prefs;

    LocalDataSource(Context context) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    List<Note> getItems() {
        return read(KEY_ITEMS);
    }

    void setItems(List<Note> items) {
        write(KEY_ITEMS, items);
    }

    List<Note> getPending() {
        return read(KEY_PENDING);
    }

    void setPending(List<Note> items) {
        write(KEY_PENDING, items);
    }

    void addItem(Note note) {
        List<Note> items = getItems();
        items.add(0, note);
        setItems(items);
    }

    void addPending(Note note) {
        List<Note> pending = getPending();
        pending.add(note);
        setPending(pending);
    }

    void markSynced(List<Note> saved) {
        Set<String> ids = new HashSet<>();
        for (Note note : saved) {
            ids.add(note.id);
        }
        List<Note> updated = new ArrayList<>();
        for (Note note : getItems()) {
            if (ids.contains(note.id)) {
                updated.add(new Note(note.id, note.text, note.createdAt, true));
            } else {
                updated.add(note);
            }
        }
        setItems(updated);
    }

    private List<Note> read(String key) {
        String raw = prefs.getString(key, "[]");
        List<Note> items = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                items.add(Note.fromJson(obj));
            }
        } catch (JSONException error) {
            return new ArrayList<>();
        }
        return items;
    }

    private void write(String key, List<Note> items) {
        JSONArray array = new JSONArray();
        for (Note item : items) {
            array.put(item.toJson());
        }
        prefs.edit().putString(key, array.toString()).apply();
    }
}

final class RemoteDataSource {
    private static final int TIMEOUT_MS = 10000;

    private final String baseUrl;

    RemoteDataSource(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    List<Note> fetchItems() throws IOException {
        HttpURLConnection connection = createConnection("GET");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("Fetch failed with code " + code);
        }
        String body = readStream(connection);
        return parseItems(body);
    }

    List<Note> pushItems(List<Note> items) throws IOException {
        HttpURLConnection connection = createConnection("POST");
        JSONObject payload = new JSONObject();
        JSONArray array = new JSONArray();
        for (Note note : items) {
            array.put(note.toJson());
        }
        try {
            payload.put("items", array);
        } catch (JSONException error) {
            throw new IOException("Invalid payload", error);
        }

        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8)
        );
        writer.write(payload.toString());
        writer.flush();
        writer.close();

        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("Push failed with code " + code);
        }
        String body = readStream(connection);
        return parseItems(body);
    }

    private HttpURLConnection createConnection(String method) throws IOException {
        URL url = new URL(baseUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Content-Type", "application/json");
        if ("POST".equals(method)) {
            connection.setDoOutput(true);
        }
        return connection;
    }

    private String readStream(HttpURLConnection connection) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)
        );
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line);
        }
        reader.close();
        return builder.toString();
    }

    private List<Note> parseItems(String raw) throws IOException {
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray items = root.optJSONArray("items");
            List<Note> result = new ArrayList<>();
            if (items == null) {
                return result;
            }
            for (int i = 0; i < items.length(); i++) {
                result.add(Note.fromJson(items.getJSONObject(i)));
            }
            return result;
        } catch (JSONException error) {
            throw new IOException("Invalid JSON", error);
        }
    }
}

final class Note {
    final String id;
    final String text;
    final long createdAt;
    final boolean synced;

    Note(String id, String text, long createdAt, boolean synced) {
        this.id = id;
        this.text = text;
        this.createdAt = createdAt;
        this.synced = synced;
    }

    JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", id);
            obj.put("text", text);
            obj.put("createdAt", createdAt);
            obj.put("synced", synced);
        } catch (JSONException ignored) {
            return new JSONObject();
        }
        return obj;
    }

    static Note fromJson(JSONObject obj) {
        return new Note(
                obj.optString("id"),
                obj.optString("text"),
                obj.optLong("createdAt", System.currentTimeMillis()),
                obj.optBoolean("synced", true)
        );
    }
}
