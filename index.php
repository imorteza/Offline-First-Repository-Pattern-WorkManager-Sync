<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Offline-First Demo</title>
    <style>
      body {
        font-family: Arial, sans-serif;
        max-width: 720px;
        margin: 32px auto;
        padding: 0 16px;
        color: #222;
      }
      h1 {
        margin-bottom: 4px;
      }
      form {
        display: flex;
        gap: 8px;
        margin: 16px 0;
      }
      input[type="text"] {
        flex: 1;
        padding: 8px;
      }
      button {
        padding: 8px 12px;
        cursor: pointer;
      }
      #status {
        font-size: 0.9rem;
        margin: 8px 0 16px;
        color: #555;
      }
      .item {
        display: flex;
        justify-content: space-between;
        padding: 6px 0;
        border-bottom: 1px solid #eee;
      }
      .badge {
        font-size: 0.75rem;
        padding: 2px 6px;
        border-radius: 10px;
        background: #eee;
      }
      .badge.synced {
        background: #d7f5d7;
      }
    </style>
  </head>
  <body>
    <h1>Offline-First Notes</h1>
    <p>Save notes locally, then sync in the background when online.</p>

    <form id="note-form">
      <input id="note-input" type="text" placeholder="Write a note" required>
      <button type="submit">Save</button>
      <button id="sync-btn" type="button">Sync now</button>
    </form>

    <div id="status">Ready.</div>
    <div id="items"></div>

    <script src="app.js"></script>
  </body>
</html>
