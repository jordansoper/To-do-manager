# To-Doer Android app

Native client for the self-hosted [To-Do Manager](../README.md) server. It shows your task tree, syncs in the background, and can notify you when:

- A task is **due today** (any nesting level, `due_date` equals today on the device).
- A **recurring** root task has been **reset** (completed → open again with a new cycle), including when the server auto-advances overdue recurring tasks.

## Requirements

- Android Studio Hedgehog (2023.1.1) or newer, or a JDK 17 + Android SDK install with Gradle 8.7+.
- Your server reachable over HTTPS (recommended) or HTTP (cleartext is enabled for local/LAN use).

## Configure the server

1. Deploy the Flask app and note its base URL (e.g. `https://todo.example.com`).
2. **Strongly recommended on the public internet:** set an API key on the server and use it in the app.

```bash
# Example: systemd drop-in or Environment= in the unit file
export TODO_API_KEY="a-long-random-secret"
```

Restart the app process after setting the variable. The Android app sends `Authorization: Bearer <key>`.

If `TODO_API_KEY` is **not** set, the JSON API is open to anyone who can reach the URL (same as the web UI).

## Build and run

1. Open the `android` folder in Android Studio (“Open” → select the `android` directory).
2. Let Gradle sync; if the Gradle wrapper is missing, use **File → Settings → Build, Execution, Deployment → Gradle** and use the bundled Gradle, or run `gradle wrapper` from the `android` directory on a machine that has Gradle installed.
3. Run the **app** configuration on a device or emulator (API 26+).

First launch: enter the **Server URL** (no trailing path; e.g. `https://todo.example.com`) and your **API key** if used, then **Save & sync**. Grant **notifications** when asked so due/recurring alerts can appear.

Background sync runs about every **15 minutes** (WorkManager minimum interval) while a URL is saved and the device has network.

## API reference (server)

| Method | Path | Notes |
|--------|------|--------|
| GET | `/api/v1/health` | No auth; returns `{"ok":true}` |
| GET | `/api/v1/todos` | JSON tree after server-side recurrence processing |
| POST | `/api/v1/todos/<id>/toggle` | Same semantics as the web “checkbox” |

Optional headers when `TODO_API_KEY` is set: `Authorization: Bearer <key>` or `X-API-Key: <key>`.
