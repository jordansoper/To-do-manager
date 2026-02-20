# To-Doer

A self-hosted task management web app with nested sub-tasks, recurring tasks, multiple lists, and a native **Home Assistant** integration.

---

## Features

### Task Management
- **Multiple lists** — Organise tasks into separate named lists. An "All Tasks" view shows every list at once.
- **Nested sub-tasks** — Add sub-tasks to any to-do item, infinitely deep. Completing all sub-tasks auto-completes the parent.
- **Progress tracking** — Visual progress bar on parent tasks shows sub-task completion percentage.
- **Due dates** — Set a due date on any task. Overdue tasks are highlighted automatically.
- **Descriptions** — Optional description field on each task.
- **Sort order** — Tasks maintain their display order.

### Recurring Tasks
- **Daily, weekly, monthly, yearly** — Standard recurrence patterns. Once a recurring task is completed, it automatically resets with the next due date.
- **Weekly day selection** — For weekly recurrence, choose which day of the week (Mon–Sun).
- **Every N days** — Custom interval: reset the task N days after each completion.
- **Auto-reset on overdue** — If a recurring task's due date passes while still completed, it automatically rolls forward to the next occurrence.

### Completion Logic
- Completing a parent task completes all its children.
- Completing all children of a parent auto-completes the parent.
- Uncompleting a child uncompletes all ancestors.
- Deleting the last child of a parent triggers the parent's auto-complete check.

### UI
- **Dark theme** — Clean, minimal dark UI with CSS variables for easy theming.
- **Responsive** — Works on desktop and mobile. Collapsible sidebar on small screens.
- **Inline editing** — Edit task title, description, recurrence, and due date without leaving the page.
- **Customisable header** — Set the app header text from the UI.
- **Progressive Web App (PWA)** — Installable on mobile/desktop via browser. Works offline via service worker caching.

### Storage & Deployment
- **SQLite** — No external database server needed. All data in a single file.
- **Systemd service** — Runs as a dedicated system user. Managed with `systemctl`.
- **Self-hosted** — Designed for Proxmox LXC containers on your local network.

---

## Install on Proxmox LXC

### 1. Create the LXC container

In the Proxmox web UI:

1. Click **Create CT**
2. Configure:
   - **Template:** Debian 12 (or Ubuntu 22.04/24.04)
   - **Disk:** 2 GB is plenty
   - **CPU:** 1 core
   - **Memory:** 256 MB (512 MB recommended)
   - **Network:** DHCP or a static IP on your LAN
3. Start the container

### 2. Log into the container

```bash
pct enter <CTID>
```

Or SSH directly:

```bash
ssh root@<container-ip>
```

### 3. Download and install

```bash
apt-get update && apt-get install -y git
git clone https://github.com/jordansoper/To-do-manager.git /tmp/todo-manager
cd /tmp/todo-manager
bash install.sh
```

The install script will:
- Install Python 3 and create a virtual environment
- Create a dedicated `todo` system user
- Copy the app to `/opt/todo-manager`
- Set up and start a systemd service

### 4. Access the app

```
http://<container-ip>:5000
```

---

## Managing the service

```bash
# Check status
systemctl status todo-manager

# View live logs
journalctl -u todo-manager -f

# Restart
systemctl restart todo-manager

# Stop
systemctl stop todo-manager
```

## Updating

```bash
cd /opt/todo-manager
git pull origin main
bash install.sh
```

The install script is safe to re-run — it updates app files and restarts the service. Your database at `/opt/todo-manager/instance/todos.db` is preserved.

## Uninstalling

```bash
bash /tmp/todo-manager/uninstall.sh
```

This removes the service, app files, and user. **Your data will be deleted.**

## Backup

The entire database is a single SQLite file:

```
/opt/todo-manager/instance/todos.db
```

Copy this file to back up all your tasks.

---

## Home Assistant Integration

The app includes a custom Home Assistant integration that exposes each of your lists as a native **Todo** entity. These render as interactive To-do cards in your HA dashboard using the built-in Todo card — no custom cards or HACS required.

Each list supports:
- Viewing all tasks (active and completed)
- Checking/unchecking tasks
- Adding new tasks
- Deleting tasks

### Requirements

- Home Assistant 2023.11 or later (when the `todo` platform was introduced)
- Your To-Do Manager instance must be reachable from your HA host

### Installation

#### 1. Copy the integration files

Copy the `home_assistant/custom_components/todo_manager/` folder from this repository into your Home Assistant `config/custom_components/` directory:

```
config/
└── custom_components/
    └── todo_manager/
        ├── __init__.py
        ├── manifest.json
        ├── config_flow.py
        ├── coordinator.py
        ├── todo.py
        ├── strings.json
        └── translations/
            └── en.json
```

You can copy them via SCP, the HA file editor, or SSH:

```bash
scp -r home_assistant/custom_components/todo_manager \
    homeassistant@<ha-ip>:/config/custom_components/
```

#### 2. Restart Home Assistant

**Settings → System → Restart**

#### 3. Add the integration

1. Go to **Settings → Devices & Services → Add Integration**
2. Search for **To-Do Manager**
3. Enter your instance URL (e.g. `http://192.168.1.100:5000`)
4. Click **Submit**

Home Assistant will connect to your instance and create one Todo entity per list.

#### 4. Add a Todo card to your dashboard

1. Edit your dashboard
2. Add card → **To-do list**
3. Select one of your To-Do Manager entities (e.g. `todo.my_tasks`)

#### How it works

- The integration polls the REST API every **30 seconds** for updates.
- Each list becomes a separate `todo.<list_name>` entity.
- Checking/unchecking a task, adding a task, or deleting a task immediately calls the API and triggers a refresh.

---

## REST API

The app exposes a JSON API for the Home Assistant integration (and any other use):

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/lists` | List all task lists |
| `GET` | `/api/lists/<id>/todos` | Get todos (with children) for a list |
| `POST` | `/api/todos` | Create a new todo |
| `POST` | `/api/todos/<id>/toggle` | Toggle completion of a todo |
| `DELETE` | `/api/todos/<id>` | Delete a todo |

### `GET /api/lists`

```json
[
  {"id": 1, "name": "My Tasks", "created_at": "2024-01-01T00:00:00"},
  {"id": 2, "name": "Shopping", "created_at": "2024-01-02T00:00:00"}
]
```

### `GET /api/lists/1/todos`

Returns top-level todos with nested `children` arrays:

```json
[
  {
    "id": 1,
    "title": "Buy groceries",
    "description": "",
    "completed": false,
    "due_date": "2024-12-01",
    "recurrence": null,
    "recurrence_day": null,
    "recurrence_interval": null,
    "list_id": 1,
    "parent_id": null,
    "sort_order": 0,
    "created_at": "2024-01-01T00:00:00",
    "completed_at": null,
    "children": []
  }
]
```

### `POST /api/todos`

Request body (JSON):

```json
{
  "title": "New task",
  "description": "Optional description",
  "list_id": 1,
  "due_date": "2024-12-25"
}
```

Returns the created todo with HTTP 201.

### `POST /api/todos/<id>/toggle`

Toggles the completion status of the todo (and handles all cascade logic). Returns the updated todo.

### `DELETE /api/todos/<id>`

Deletes the todo and all its children.

```json
{"success": true}
```

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Python 3, Flask 3.1, SQLite3 |
| Date handling | python-dateutil |
| Production server | Gunicorn |
| Frontend | Vanilla HTML/CSS/JavaScript |
| PWA | Service Worker, Web App Manifest |
| Deployment | systemd, Python venv |

---

## Development

To run locally:

```bash
pip install -r requirements.txt
python app.py
```

The app runs on `http://localhost:5000` with debug mode enabled.
