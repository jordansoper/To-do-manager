import sqlite3
import os
from datetime import datetime, timedelta
from dateutil.relativedelta import relativedelta
from flask import Flask, render_template, request, redirect, url_for, jsonify, g

app = Flask(__name__)
app.config["DATABASE"] = os.path.join(app.instance_path, "todos.db")

os.makedirs(app.instance_path, exist_ok=True)


def get_db():
    if "db" not in g:
        g.db = sqlite3.connect(app.config["DATABASE"])
        g.db.row_factory = sqlite3.Row
        g.db.execute("PRAGMA foreign_keys = ON")
    return g.db


@app.teardown_appcontext
def close_db(exception):
    db = g.pop("db", None)
    if db is not None:
        db.close()


def init_db():
    db = get_db()
    db.execute(
        """
        CREATE TABLE IF NOT EXISTS todos (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL,
            description TEXT DEFAULT '',
            completed INTEGER DEFAULT 0,
            parent_id INTEGER,
            recurrence TEXT CHECK(recurrence IN (NULL, 'daily', 'weekly', 'monthly', 'yearly')),
            due_date TEXT,
            created_at TEXT DEFAULT (datetime('now')),
            completed_at TEXT,
            sort_order INTEGER DEFAULT 0,
            list_id INTEGER DEFAULT 1,
            FOREIGN KEY (parent_id) REFERENCES todos(id) ON DELETE CASCADE
        )
        """
    )
    db.execute(
        """
        CREATE TABLE IF NOT EXISTS lists (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            created_at TEXT DEFAULT (datetime('now'))
        )
        """
    )
    db.execute(
        """
        CREATE TABLE IF NOT EXISTS settings (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        )
        """
    )
    # Ensure default list exists
    existing = db.execute("SELECT id FROM lists WHERE id = 1").fetchone()
    if not existing:
        db.execute("INSERT INTO lists (id, name) VALUES (1, 'My Tasks')")
    # Ensure default header setting exists
    existing = db.execute("SELECT key FROM settings WHERE key = 'header'").fetchone()
    if not existing:
        db.execute("INSERT INTO settings (key, value) VALUES ('header', 'To-Do Manager')")
    # Add list_id column if upgrading from old schema
    try:
        db.execute("ALTER TABLE todos ADD COLUMN list_id INTEGER DEFAULT 1")
    except sqlite3.OperationalError:
        pass  # Column already exists
    db.commit()


with app.app_context():
    init_db()


def todo_to_dict(row):
    return {
        "id": row["id"],
        "title": row["title"],
        "description": row["description"],
        "completed": bool(row["completed"]),
        "parent_id": row["parent_id"],
        "recurrence": row["recurrence"],
        "due_date": row["due_date"],
        "created_at": row["created_at"],
        "completed_at": row["completed_at"],
        "sort_order": row["sort_order"],
        "list_id": row["list_id"],
    }


def get_children(db, parent_id):
    rows = db.execute(
        "SELECT * FROM todos WHERE parent_id = ? ORDER BY sort_order, created_at",
        (parent_id,),
    ).fetchall()
    children = []
    for row in rows:
        child = todo_to_dict(row)
        child["children"] = get_children(db, child["id"])
        children.append(child)
    return children


def build_todo_tree(db, list_id=1):
    """Build a tree of top-level todos with nested children, filtered by list."""
    roots = db.execute(
        "SELECT * FROM todos WHERE parent_id IS NULL AND list_id = ? ORDER BY sort_order, created_at",
        (list_id,),
    ).fetchall()
    tree = []
    for row in roots:
        todo = todo_to_dict(row)
        todo["children"] = get_children(db, todo["id"])
        tree.append(todo)
    return tree


def all_children_completed(db, parent_id):
    """Check if all children of a parent are completed."""
    children = db.execute(
        "SELECT id, completed FROM todos WHERE parent_id = ?", (parent_id,)
    ).fetchall()
    if not children:
        return False
    return all(c["completed"] for c in children)


def propagate_completion(db, todo_id):
    """When a child is completed, check if the parent should auto-complete."""
    row = db.execute("SELECT parent_id FROM todos WHERE id = ?", (todo_id,)).fetchone()
    if row and row["parent_id"]:
        parent_id = row["parent_id"]
        if all_children_completed(db, parent_id):
            now = datetime.utcnow().isoformat()
            db.execute(
                "UPDATE todos SET completed = 1, completed_at = ? WHERE id = ?",
                (now, parent_id),
            )
            # Recurse upward
            propagate_completion(db, parent_id)


def uncomplete_parent_chain(db, todo_id):
    """When a child is uncompleted, uncomplete all ancestors."""
    row = db.execute("SELECT parent_id FROM todos WHERE id = ?", (todo_id,)).fetchone()
    if row and row["parent_id"]:
        parent_id = row["parent_id"]
        db.execute(
            "UPDATE todos SET completed = 0, completed_at = NULL WHERE id = ?",
            (parent_id,),
        )
        uncomplete_parent_chain(db, parent_id)


def compute_next_due(recurrence, current_due):
    """Compute the next due date based on recurrence type."""
    if current_due:
        base = datetime.fromisoformat(current_due)
    else:
        base = datetime.utcnow()

    if recurrence == "daily":
        return (base + timedelta(days=1)).date().isoformat()
    elif recurrence == "weekly":
        return (base + timedelta(weeks=1)).date().isoformat()
    elif recurrence == "monthly":
        return (base + relativedelta(months=1)).date().isoformat()
    elif recurrence == "yearly":
        return (base + relativedelta(years=1)).date().isoformat()
    return None


def uncomplete_recursive(db, todo_id):
    """Reset a todo and all its children to uncompleted."""
    db.execute(
        "UPDATE todos SET completed = 0, completed_at = NULL WHERE id = ?",
        (todo_id,),
    )
    children = db.execute(
        "SELECT id FROM todos WHERE parent_id = ?", (todo_id,)
    ).fetchall()
    for child in children:
        uncomplete_recursive(db, child["id"])


def handle_recurrence(db, todo_id):
    """If a completed todo has recurrence, reset it and set next due date."""
    todo = db.execute("SELECT * FROM todos WHERE id = ?", (todo_id,)).fetchone()
    if not todo or not todo["recurrence"]:
        return

    # Find the top-level ancestor to check recurrence at the root
    root_id = todo_id
    current = todo
    while current["parent_id"]:
        current = db.execute(
            "SELECT * FROM todos WHERE id = ?", (current["parent_id"],)
        ).fetchone()
        root_id = current["id"]

    root = db.execute("SELECT * FROM todos WHERE id = ?", (root_id,)).fetchone()
    if root["completed"] and root["recurrence"]:
        next_due = compute_next_due(root["recurrence"], root["due_date"])
        db.execute(
            "UPDATE todos SET due_date = ? WHERE id = ?",
            (next_due, root_id),
        )
        uncomplete_recursive(db, root_id)


def get_setting(db, key, default=""):
    row = db.execute("SELECT value FROM settings WHERE key = ?", (key,)).fetchone()
    return row["value"] if row else default


def set_setting(db, key, value):
    db.execute(
        "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)",
        (key, value),
    )
    db.commit()


# --- Routes ---


@app.route("/")
def index():
    db = get_db()
    list_id = request.args.get("list", 1, type=int)
    process_overdue_recurrences(db)
    todos = build_todo_tree(db, list_id)
    active_todos = [t for t in todos if not t["completed"]]
    completed_todos = [t for t in todos if t["completed"]]
    lists = db.execute("SELECT * FROM lists ORDER BY created_at").fetchall()
    header = get_setting(db, "header", "To-Do Manager")
    today = datetime.utcnow().date().isoformat()
    # Verify list exists
    current_list = db.execute("SELECT * FROM lists WHERE id = ?", (list_id,)).fetchone()
    if not current_list:
        return redirect(url_for("index", list=1))
    return render_template(
        "index.html",
        active_todos=active_todos,
        completed_todos=completed_todos,
        lists=lists,
        current_list_id=list_id,
        current_list=current_list,
        header=header,
        now_date=today,
    )


def process_overdue_recurrences(db):
    """Auto-reset recurring todos that are past their due date."""
    today = datetime.utcnow().date().isoformat()
    overdue = db.execute(
        """
        SELECT * FROM todos
        WHERE recurrence IS NOT NULL
          AND parent_id IS NULL
          AND due_date IS NOT NULL
          AND due_date < ?
          AND completed = 1
        """,
        (today,),
    ).fetchall()
    for todo in overdue:
        next_due = compute_next_due(todo["recurrence"], todo["due_date"])
        # Keep advancing until due date is today or future
        while next_due and next_due < today:
            next_due = compute_next_due(todo["recurrence"], next_due)
        db.execute(
            "UPDATE todos SET due_date = ? WHERE id = ?", (next_due, todo["id"])
        )
        uncomplete_recursive(db, todo["id"])
    db.commit()


@app.route("/add", methods=["POST"])
def add_todo():
    title = request.form.get("title", "").strip()
    if not title:
        list_id = request.form.get("list_id", 1, type=int)
        return redirect(url_for("index", list=list_id))

    description = request.form.get("description", "").strip()
    parent_id = request.form.get("parent_id") or None
    recurrence = request.form.get("recurrence") or None
    due_date = request.form.get("due_date") or None
    list_id = request.form.get("list_id", 1, type=int)

    if parent_id:
        parent_id = int(parent_id)
        # Sub-todos don't have their own recurrence
        recurrence = None
        due_date = None

    db = get_db()
    db.execute(
        """
        INSERT INTO todos (title, description, parent_id, recurrence, due_date, list_id)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        (title, description, parent_id, recurrence, due_date, list_id),
    )
    db.commit()
    return redirect(url_for("index", list=list_id))


@app.route("/toggle/<int:todo_id>", methods=["POST"])
def toggle_todo(todo_id):
    db = get_db()
    todo = db.execute("SELECT * FROM todos WHERE id = ?", (todo_id,)).fetchone()
    if not todo:
        return redirect(url_for("index"))

    list_id = todo["list_id"]
    new_status = 0 if todo["completed"] else 1

    if new_status == 1:
        now = datetime.utcnow().isoformat()
        # Complete this todo and all its children
        complete_recursive(db, todo_id, now)
        # Check if parent should auto-complete
        propagate_completion(db, todo_id)
        db.commit()
        # Handle recurrence if the root got completed
        handle_recurrence(db, todo_id)
        db.commit()
    else:
        db.execute(
            "UPDATE todos SET completed = 0, completed_at = NULL WHERE id = ?",
            (todo_id,),
        )
        uncomplete_parent_chain(db, todo_id)
        db.commit()

    return redirect(url_for("index", list=list_id))


def complete_recursive(db, todo_id, timestamp):
    """Mark a todo and all its children as completed."""
    db.execute(
        "UPDATE todos SET completed = 1, completed_at = ? WHERE id = ?",
        (timestamp, todo_id),
    )
    children = db.execute(
        "SELECT id FROM todos WHERE parent_id = ?", (todo_id,)
    ).fetchall()
    for child in children:
        complete_recursive(db, child["id"], timestamp)


@app.route("/edit/<int:todo_id>", methods=["POST"])
def edit_todo(todo_id):
    db = get_db()
    title = request.form.get("title", "").strip()
    if not title:
        return redirect(url_for("index"))

    description = request.form.get("description", "").strip()
    recurrence = request.form.get("recurrence") or None
    due_date = request.form.get("due_date") or None

    todo = db.execute("SELECT * FROM todos WHERE id = ?", (todo_id,)).fetchone()
    if not todo:
        return redirect(url_for("index"))

    list_id = todo["list_id"]

    # Sub-todos don't have their own recurrence
    if todo["parent_id"]:
        recurrence = None
        due_date = None

    db.execute(
        """
        UPDATE todos SET title = ?, description = ?, recurrence = ?, due_date = ?
        WHERE id = ?
        """,
        (title, description, recurrence, due_date, todo_id),
    )
    db.commit()
    return redirect(url_for("index", list=list_id))


@app.route("/delete/<int:todo_id>", methods=["POST"])
def delete_todo(todo_id):
    db = get_db()
    todo = db.execute("SELECT parent_id, list_id FROM todos WHERE id = ?", (todo_id,)).fetchone()
    list_id = todo["list_id"] if todo else 1
    db.execute("DELETE FROM todos WHERE id = ?", (todo_id,))
    db.commit()

    # After deleting, check if parent should auto-complete
    if todo and todo["parent_id"]:
        remaining = db.execute(
            "SELECT id FROM todos WHERE parent_id = ?", (todo["parent_id"],)
        ).fetchall()
        if remaining and all_children_completed(db, todo["parent_id"]):
            now = datetime.utcnow().isoformat()
            db.execute(
                "UPDATE todos SET completed = 1, completed_at = ? WHERE id = ?",
                (now, todo["parent_id"]),
            )
            propagate_completion(db, todo["parent_id"])
            db.commit()

    return redirect(url_for("index", list=list_id))


# --- List management routes ---


@app.route("/lists/add", methods=["POST"])
def add_list():
    name = request.form.get("name", "").strip()
    if not name:
        return redirect(url_for("index"))
    db = get_db()
    cursor = db.execute("INSERT INTO lists (name) VALUES (?)", (name,))
    db.commit()
    return redirect(url_for("index", list=cursor.lastrowid))


@app.route("/lists/rename/<int:list_id>", methods=["POST"])
def rename_list(list_id):
    name = request.form.get("name", "").strip()
    if not name:
        return redirect(url_for("index", list=list_id))
    db = get_db()
    db.execute("UPDATE lists SET name = ? WHERE id = ?", (name, list_id))
    db.commit()
    return redirect(url_for("index", list=list_id))


@app.route("/lists/delete/<int:list_id>", methods=["POST"])
def delete_list(list_id):
    if list_id == 1:
        return redirect(url_for("index", list=1))
    db = get_db()
    db.execute("DELETE FROM todos WHERE list_id = ?", (list_id,))
    db.execute("DELETE FROM lists WHERE id = ?", (list_id,))
    db.commit()
    return redirect(url_for("index", list=1))


# --- Settings routes ---


@app.route("/settings/header", methods=["POST"])
def update_header():
    header = request.form.get("header", "").strip()
    list_id = request.form.get("list_id", 1, type=int)
    if header:
        db = get_db()
        set_setting(db, "header", header)
    return redirect(url_for("index", list=list_id))


if __name__ == "__main__":
    app.run(debug=True, host="0.0.0.0", port=5000)
