import sqlite3
import os
from functools import wraps
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
            FOREIGN KEY (parent_id) REFERENCES todos(id) ON DELETE CASCADE
        )
        """
    )
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


def build_todo_tree(db):
    """Build a tree of top-level todos with nested children."""
    roots = db.execute(
        "SELECT * FROM todos WHERE parent_id IS NULL ORDER BY sort_order, created_at"
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


# --- API (mobile app) ---


def require_api_auth(f):
    """If TODO_API_KEY is set, require Bearer token or X-API-Key header."""

    @wraps(f)
    def wrapped(*args, **kwargs):
        expected = os.environ.get("TODO_API_KEY")
        if not expected:
            return f(*args, **kwargs)
        token = None
        auth = request.headers.get("Authorization", "")
        if auth.startswith("Bearer "):
            token = auth[7:].strip()
        if not token:
            token = (request.headers.get("X-API-Key") or "").strip()
        if token != expected:
            return jsonify(error="unauthorized"), 401
        return f(*args, **kwargs)

    return wrapped


@app.route("/api/v1/health", methods=["GET"])
def api_health():
    return jsonify(ok=True)


@app.route("/api/v1/todos", methods=["GET"])
@require_api_auth
def api_list_todos():
    db = get_db()
    process_overdue_recurrences(db)
    todos = build_todo_tree(db)
    return jsonify(
        server_time=datetime.utcnow().isoformat() + "Z",
        todos=todos,
    )


@app.route("/api/v1/todos/<int:todo_id>/toggle", methods=["POST"])
@require_api_auth
def api_toggle_todo(todo_id):
    db = get_db()
    todo = db.execute("SELECT * FROM todos WHERE id = ?", (todo_id,)).fetchone()
    if not todo:
        return jsonify(error="not_found"), 404

    new_status = 0 if todo["completed"] else 1

    if new_status == 1:
        now = datetime.utcnow().isoformat()
        complete_recursive(db, todo_id, now)
        propagate_completion(db, todo_id)
        db.commit()
        handle_recurrence(db, todo_id)
        db.commit()
    else:
        db.execute(
            "UPDATE todos SET completed = 0, completed_at = NULL WHERE id = ?",
            (todo_id,),
        )
        uncomplete_parent_chain(db, todo_id)
        db.commit()

    row = db.execute("SELECT * FROM todos WHERE id = ?", (todo_id,)).fetchone()
    return jsonify(ok=True, todo=todo_to_dict(row))


# --- Routes ---


@app.route("/")
def index():
    db = get_db()
    process_overdue_recurrences(db)
    todos = build_todo_tree(db)
    today = datetime.utcnow().date().isoformat()
    return render_template("index.html", todos=todos, now_date=today)


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
        return redirect(url_for("index"))

    description = request.form.get("description", "").strip()
    parent_id = request.form.get("parent_id") or None
    recurrence = request.form.get("recurrence") or None
    due_date = request.form.get("due_date") or None

    if parent_id:
        parent_id = int(parent_id)
        # Sub-todos don't have their own recurrence
        recurrence = None
        due_date = None

    db = get_db()
    cur = db.execute(
        """
        INSERT INTO todos (title, description, parent_id, recurrence, due_date)
        VALUES (?, ?, ?, ?, ?)
        """,
        (title, description, parent_id, recurrence, due_date),
    )
    db.commit()
    new_id = cur.lastrowid
    if (
        request.headers.get("X-Requested-With") == "XMLHttpRequest"
        and parent_id
    ):
        return jsonify(ok=True, id=new_id)
    return redirect(url_for("index"))


@app.route("/toggle/<int:todo_id>", methods=["POST"])
def toggle_todo(todo_id):
    db = get_db()
    todo = db.execute("SELECT * FROM todos WHERE id = ?", (todo_id,)).fetchone()
    if not todo:
        return redirect(url_for("index"))

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

    return redirect(url_for("index"))


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
    return redirect(url_for("index"))


@app.route("/delete/<int:todo_id>", methods=["POST"])
def delete_todo(todo_id):
    db = get_db()
    todo = db.execute("SELECT parent_id FROM todos WHERE id = ?", (todo_id,)).fetchone()
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

    return redirect(url_for("index"))


if __name__ == "__main__":
    app.run(debug=True, host="0.0.0.0", port=5000)
