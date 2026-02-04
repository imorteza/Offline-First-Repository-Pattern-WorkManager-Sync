(() => {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const statusEl = $("status");
  const itemsEl = $("items");
  const formEl = $("note-form");
  const inputEl = $("note-input");
  const syncBtn = $("sync-btn");

  const STORAGE_KEYS = {
    items: "offline_items",
    pending: "offline_pending",
  };

  class LocalDataSource {
    constructor(keys) {
      this.keys = keys;
    }

    read(key, fallback) {
      try {
        return JSON.parse(localStorage.getItem(key)) ?? fallback;
      } catch (error) {
        return fallback;
      }
    }

    write(key, value) {
      localStorage.setItem(key, JSON.stringify(value));
    }

    getItems() {
      return this.read(this.keys.items, []);
    }

    setItems(items) {
      this.write(this.keys.items, items);
    }

    getPending() {
      return this.read(this.keys.pending, []);
    }

    setPending(pending) {
      this.write(this.keys.pending, pending);
    }

    addItem(item) {
      const items = this.getItems();
      items.unshift(item);
      this.setItems(items);
    }

    addPending(item) {
      const pending = this.getPending();
      pending.push(item);
      this.setPending(pending);
    }

    markSynced(ids) {
      const idSet = new Set(ids);
      const updated = this.getItems().map((item) =>
        idSet.has(item.id) ? { ...item, synced: true } : item
      );
      this.setItems(updated);
    }
  }

  class RemoteDataSource {
    async fetchItems() {
      const response = await fetch("api.php", { method: "GET" });
      if (!response.ok) {
        throw new Error("Failed to fetch items");
      }
      const data = await response.json();
      return Array.isArray(data.items) ? data.items : [];
    }

    async pushItems(items) {
      const response = await fetch("api.php", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ items }),
      });
      if (!response.ok) {
        throw new Error("Failed to push items");
      }
      const data = await response.json();
      return Array.isArray(data.items) ? data.items : [];
    }
  }

  class NotesRepository {
    constructor(local, remote) {
      this.local = local;
      this.remote = remote;
    }

    getLocalItems() {
      return this.local.getItems();
    }

    add(text) {
      const item = {
        id: this.createLocalId(),
        text,
        createdAt: Date.now(),
        synced: false,
      };
      this.local.addItem(item);
      this.local.addPending(item);
      return item;
    }

    async sync() {
      if (!navigator.onLine) {
        return { skipped: true };
      }

      const pending = this.local.getPending();
      if (pending.length > 0) {
        const saved = await this.remote.pushItems(pending);
        const savedIds = saved.map((item) => item.id);
        this.local.markSynced(savedIds);
        this.local.setPending([]);
        this.mergeRemote(saved);
      }

      const remoteItems = await this.remote.fetchItems();
      this.mergeRemote(remoteItems);
      return { synced: true };
    }

    mergeRemote(remoteItems) {
      const localItems = this.local.getItems();
      const byId = new Map();

      localItems.forEach((item) => byId.set(item.id, item));
      remoteItems.forEach((item) => {
        const existing = byId.get(item.id);
        byId.set(item.id, {
          ...existing,
          ...item,
          synced: true,
        });
      });

      const merged = Array.from(byId.values()).sort(
        (a, b) => b.createdAt - a.createdAt
      );
      this.local.setItems(merged);
    }

    createLocalId() {
      return `local_${Date.now()}_${Math.random().toString(16).slice(2)}`;
    }
  }

  class WorkManager {
    constructor(repository, intervalMs) {
      this.repository = repository;
      this.intervalMs = intervalMs;
      this.timer = null;
    }

    start() {
      this.stop();
      this.timer = window.setInterval(() => this.run(), this.intervalMs);
      window.addEventListener("online", () => this.run());
    }

    stop() {
      if (this.timer) {
        window.clearInterval(this.timer);
        this.timer = null;
      }
    }

    async run() {
      try {
        setStatus("Syncing...");
        const result = await this.repository.sync();
        render();
        if (result.skipped) {
          setStatus("Offline. Changes saved locally.");
        } else {
          setStatus(`Synced at ${new Date().toLocaleTimeString()}.`);
        }
      } catch (error) {
        setStatus("Sync failed. Will retry.");
      }
    }
  }

  const repository = new NotesRepository(
    new LocalDataSource(STORAGE_KEYS),
    new RemoteDataSource()
  );
  const workManager = new WorkManager(repository, 15000);

  function render() {
    const items = repository.getLocalItems();
    itemsEl.innerHTML = "";

    if (items.length === 0) {
      itemsEl.textContent = "No notes yet.";
      return;
    }

    items.forEach((item) => {
      const row = document.createElement("div");
      row.className = "item";

      const text = document.createElement("span");
      text.textContent = item.text;

      const badge = document.createElement("span");
      badge.className = `badge${item.synced ? " synced" : ""}`;
      badge.textContent = item.synced ? "synced" : "pending";

      row.appendChild(text);
      row.appendChild(badge);
      itemsEl.appendChild(row);
    });
  }

  function setStatus(message) {
    statusEl.textContent = message;
  }

  formEl.addEventListener("submit", (event) => {
    event.preventDefault();
    const value = inputEl.value.trim();
    if (!value) {
      return;
    }

    repository.add(value);
    inputEl.value = "";
    render();
    workManager.run();
  });

  syncBtn.addEventListener("click", () => workManager.run());

  render();
  workManager.start();
  workManager.run();
})();
