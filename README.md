# To-Doer

A self-hosted task management application with support for **nested sub-tasks** and **recurring tasks**.

## Features

- **Nested to-dos** — Add sub-tasks to any to-do. When all sub-tasks are completed, the parent auto-completes.
- **Recurring tasks** — Set a to-do to repeat daily, weekly, monthly, or yearly. Once completed, it automatically resets with the next due date.
- **Progress tracking** — Visual progress bar shows sub-task completion.
- **Dark UI** — Clean, minimal dark theme that works on desktop and mobile.
- **SQLite database** — No external database server needed. Data is stored in a single file.

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

From the Proxmox shell or SSH:

```bash
pct enter <CTID>
```

Or SSH directly if you set up networking:

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

Open your browser and go to:

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
cd /tmp/todo-manager
git pull
bash install.sh
```

The install script is safe to re-run — it will update the app files and restart the service. Your database in `/opt/todo-manager/instance/todos.db` is preserved.

## Uninstalling

```bash
bash /tmp/todo-manager/uninstall.sh
```

This removes the service, app files, and user. Your data will be deleted.

## Backup

The entire database is a single SQLite file:

```
/opt/todo-manager/instance/todos.db
```

Copy this file to back up all your tasks.

---

## Development

To run locally for development:

```bash
pip install -r requirements.txt
python app.py
```

The app runs on `http://localhost:5000` with debug mode enabled.
