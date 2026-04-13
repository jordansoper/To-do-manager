import calendar
import logging
import os
import sqlite3
import subprocess
import sys
from functools import wraps
from datetime import date, datetime, timedelta

from flask import (
    Flask,
    abort,
    g,
    has_request_context,
    jsonify,
    redirect,
    render_template,
    request,
    url_for,
)
from flask.signals import got_request_exception
from werkzeug.exceptions import HTTPException

# Resolve paths from this file so templates/instance work even if WorkingDirectory/CWD is wrong
# (common cause of TemplateNotFound → 500 under systemd/gunicorn).
_APP_DIR = os.path.dirname(os.path.abspath(__file__))
_INSTANCE_DIR = os.path.join(_APP_DIR, "instance")

_DEPLOY_SCRIPT = "/usr/local/bin/todo-manager-deploy"

app = Flask(
    __name__,
    template_folder=os.path.join(_APP_DIR, "templates"),
    instance_path=_INSTANCE_DIR,
)
app.config["DATABASE"] = os.path.join(app.instance_path, "todos.db")

os.makedirs(app.instance_path, exist_ok=True)

if not app.logger.handlers:
    _h = logging.StreamHandler(sys.stderr)
    _h.setFormatter(
        logging.Formatter("%(asctime)s %(levelname)s [%(name)s] %(message)s")
    )
    app.logger.addHandler(_h)
app.logger.setLevel(logging.INFO)


def get_db():
    if "db" not in g:
        g.db = sqlite3.connect(app.config["DATABASE"])
        g.db.row_factory = sqlite3.Row
        g.db.execute("PRAGMA foreign_keys = ON")
        # WAL + busy timeout: SQLite is poor with concurrent writers; gunicorn multi-worker
        # + default journal mode often yields "database is locked" (500) on small hosts.
        try:
            g.db.execute("PRAGMA journal_mode=WAL")
        except sqlite3.Error:
            app.logger.warning("SQLite WAL unavailable; using default journal mode")
        g.db.execute("PRAGMA busy_timeout=8000")
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
            repeat_date TEXT,
            created_at TEXT DEFAULT (datetime('now')),
            completed_at TEXT,
            sort_order INTEGER DEFAULT 0,
            FOREIGN KEY (parent_id) REFERENCES todos(id) ON DELETE CASCADE
        )
        """
    )
    db.execute(
        """
        CREATE TABLE IF NOT EXISTS lists (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            sort_order INTEGER DEFAULT 0
        )
        """
    )
    migrate_lists_schema(db)
    db.commit()


def migrate_lists_schema(db):
    """Add lists + todos columns for existing databases (CREATE TABLE IF NOT EXISTS skips old tables)."""
    lists_cols = [r["name"] for r in db.execute("PRAGMA table_info(lists)").fetchall()]
    if lists_cols and "sort_order" not in lists_cols:
        db.execute("ALTER TABLE lists ADD COLUMN sort_order INTEGER DEFAULT 0")

    if db.execute("SELECT COUNT(*) FROM lists").fetchone()[0] == 0:
        db.execute("INSERT INTO lists (id, name, sort_order) VALUES (1, 'General', 0)")

    cols = [r["name"] for r in db.execute("PRAGMA table_info(todos)").fetchall()]
    if "list_id" not in cols:
        db.execute("ALTER TABLE todos ADD COLUMN list_id INTEGER DEFAULT 1")
    if "sort_order" not in cols:
        db.execute("ALTER TABLE todos ADD COLUMN sort_order INTEGER DEFAULT 0")
    if "repeat_date" not in cols:
        db.execute("ALTER TABLE todos ADD COLUMN repeat_date TEXT")
        db.execute(
            """
            UPDATE todos
            SET repeat_date = due_date
            WHERE recurrence IS NOT NULL AND due_date IS NOT NULL
            """
        )


with app.app_context():
    init_db()


@got_request_exception.connect_via(app)
def _log_request_exception(sender, exception, **extra):
    """Log real errors without replacing Flask/Werkzeug's own HTTP error handling."""
    try:
        if isinstance(exception, HTTPException):
            return
        if has_request_context():
            sender.logger.exception("%s %s", request.method, request.path)
        else:
            sender.logger.exception("Unhandled exception (outside request context)")
    except Exception:
        # Never interfere with Flask's error response
        pass


def _normalize_recurrence(val):
    if val is None:
        return None
    v = str(val).strip().lower()
    if v in ("daily", "weekly", "monthly", "yearly"):
        return v
    return None


def _normalize_due_date(val):
    if val is None:
        return None
    v = str(val).strip()
    return v if v else None


def _coerce_recurrence_dates(recurrence, due_date, repeat_date):
    """
    If recurrence is set, both due_date and repeat_date are required (when the task is
    due vs when it reappears on the repeat cycle). If no recurrence, repeat_date is cleared.
    """
    if recurrence:
        if not due_date or not repeat_date:
            raise ValueError("recurrence requires due_date and repeat_date")
        return recurrence, due_date, repeat_date
    return None, due_date, None


def todo_to_dict(row):
    d = {
        "id": row["id"],
        "title": row["title"],
        "description": row["description"],
        "completed": bool(row["completed"]),
        "parent_id": row["parent_id"],
        "recurrence": row["recurrence"],
        "due_date": row["due_date"],
        "created_at": row["created_at"],
        "completed_at": row["completed_at"],
    }
    try:
        d["repeat_date"] = row["repeat_date"]
    except (KeyError, IndexError):
        d["repeat_date"] = None
    try:
        d["sort_order"] = row["sort_order"]
    except (KeyError, IndexError):
        d["sort_order"] = 0
    try:
        d["list_id"] = row["list_id"]
    except (KeyError, IndexError):
        d["list_id"] = 1
    return d


def get_children(db, parent_id):
    rows = db.execute(
        """
        SELECT * FROM todos
        WHERE parent_id = ? AND completed = 0
        ORDER BY sort_order, created_at
        """,
        (parent_id,),
    ).fetchall()
    children = []
    for row in rows:
        child = todo_to_dict(row)
        child["children"] = get_children(db, child["id"])
        children.append(child)
    return children


def build_subtask_stats(db, list_id=None):
    """Per parent_id: done count and total subtasks (for progress while active list hides completed)."""
    if list_id is None:
        rows = db.execute(
            """
            SELECT parent_id,
                   SUM(CASE WHEN completed = 1 THEN 1 ELSE 0 END) AS done,
                   COUNT(*) AS total
            FROM todos WHERE parent_id IS NOT NULL
            GROUP BY parent_id
            """
        ).fetchall()
    else:
        rows = db.execute(
            """
            SELECT parent_id,
                   SUM(CASE WHEN completed = 1 THEN 1 ELSE 0 END) AS done,
                   COUNT(*) AS total
            FROM todos WHERE parent_id IS NOT NULL AND list_id = ?
            GROUP BY parent_id
            """,
            (list_id,),
        ).fetchall()
    out = {}
    for r in rows:
        out[r["parent_id"]] = {
            "done": int(r["done"] or 0),
            "total": int(r["total"] or 0),
        }
    return out


def enrich_tree_subtask_stats(tree, stats):
    def walk(nodes):
        for t in nodes:
            s = stats.get(t["id"], {"done": 0, "total": 0})
            t["sub_done"] = s["done"]
            t["sub_total"] = s["total"]
            ch = t.get("children") or []
            if ch:
                walk(ch)

    walk(tree)


def build_todo_tree(db, list_id=None):
    """Build a tree of incomplete todos only (completed items appear in bottom sections)."""
    if list_id is None:
        roots = db.execute(
            """
            SELECT * FROM todos WHERE parent_id IS NULL AND completed = 0
            ORDER BY list_id, sort_order, created_at
            """
        ).fetchall()
    else:
        roots = db.execute(
            """
            SELECT * FROM todos WHERE parent_id IS NULL AND list_id = ? AND completed = 0
            ORDER BY sort_order, created_at
            """,
            (list_id,),
        ).fetchall()
    tree = []
    for row in roots:
        todo = todo_to_dict(row)
        todo["children"] = get_children(db, todo["id"])
        tree.append(todo)
    return tree


def get_completed_one_time(db, list_id):
    rows = db.execute(
        """
        SELECT * FROM todos
        WHERE list_id = ? AND completed = 1
          AND (recurrence IS NULL OR recurrence = '')
        ORDER BY COALESCE(completed_at, created_at) DESC, id DESC
        """,
        (list_id,),
    ).fetchall()
    return [todo_to_dict(r) for r in rows]


def get_recurring_dormant(db, list_id):
    rows = db.execute(
        """
        SELECT * FROM todos
        WHERE list_id = ? AND completed = 1
          AND recurrence IS NOT NULL AND recurrence != ''
        ORDER BY repeat_date ASC, id
        """,
        (list_id,),
    ).fetchall()
    return [todo_to_dict(r) for r in rows]


def get_all_lists(db):
    rows = db.execute("SELECT * FROM lists ORDER BY sort_order, id").fetchall()
    return [{"id": r["id"], "name": r["name"], "sort_order": r["sort_order"]} for r in rows]


def get_list_sections(db):
    """For 'all tasks' view: each list with its own active tree and completed buckets."""
    sections = []
    for lst in get_all_lists(db):
        lid = lst["id"]
        tree = build_todo_tree(db, list_id=lid)
        enrich_tree_subtask_stats(tree, build_subtask_stats(db, lid))
        sections.append(
            {
                "id": lid,
                "name": lst["name"],
                "todos": tree,
                "completed_one_time": get_completed_one_time(db, lid),
                "recurring_waiting": get_recurring_dormant(db, lid),
            }
        )
    return sections


def redirect_to_index():
    """After POST, return to the list we came from (form field or query)."""
    lst = request.form.get("list") or request.args.get("list") or "1"
    return redirect(url_for("index", list=lst))


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


def _parse_due_date(value):
    """Parse stored due_date into a date; tolerate missing or bad values."""
    if not value or not isinstance(value, str):
        return None
    s = value.strip()
    if not s:
        return None
    try:
        dt = datetime.fromisoformat(s.replace("Z", "+00:00"))
        if isinstance(dt, datetime):
            return dt.date()
    except ValueError:
        pass
    try:
        return date.fromisoformat(s[:10])
    except ValueError:
        return None


def _add_months(d, n):
    m = d.month - 1 + n
    y = d.year + m // 12
    m = m % 12 + 1
    last = calendar.monthrange(y, m)[1]
    day = min(d.day, last)
    return date(y, m, day)


def _add_years(d, n):
    y = d.year + n
    try:
        return d.replace(year=y)
    except ValueError:
        last = calendar.monthrange(y, d.month)[1]
        return date(y, d.month, min(d.day, last))


def compute_next_due(recurrence, current_due):
    """Compute the next due date based on recurrence type."""
    d = _parse_due_date(current_due) if current_due else None
    if d is None:
        d = datetime.utcnow().date()

    if recurrence == "daily":
        return (d + timedelta(days=1)).isoformat()
    if recurrence == "weekly":
        return (d + timedelta(weeks=1)).isoformat()
    if recurrence == "monthly":
        return _add_months(d, 1).isoformat()
    if recurrence == "yearly":
        return _add_years(d, 1).isoformat()
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


def _repeat_anchor(todo):
    """Date that drives the repeat cycle (legacy rows may only have due_date)."""
    try:
        rd = todo["repeat_date"]
    except (KeyError, IndexError):
        rd = None
    return rd or todo["due_date"]


def handle_recurrence(db, todo_id):
    """Advance next repeat/due dates; task stays completed until repeat_date (see process_overdue_recurrences)."""
    todo = db.execute("SELECT * FROM todos WHERE id = ?", (todo_id,)).fetchone()
    if not todo or not todo["recurrence"] or not todo["completed"]:
        return

    rec = todo["recurrence"]
    next_repeat = compute_next_due(rec, _repeat_anchor(todo))
    next_due = compute_next_due(rec, todo["due_date"])
    db.execute(
        "UPDATE todos SET repeat_date = ?, due_date = ? WHERE id = ?",
        (next_repeat, next_due, todo_id),
    )


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
    template_ok = os.path.isfile(os.path.join(_APP_DIR, "templates", "index.html"))
    try:
        db = get_db()
        db.execute("SELECT 1").fetchone()
        db_ok = True
        db_detail = None
    except (OSError, sqlite3.Error) as e:
        db_ok = False
        db_detail = str(e)
        app.logger.exception("health check failed")

    if not template_ok:
        return (
            jsonify(
                ok=False,
                database="error" if not db_ok else "ok",
                templates="missing",
                detail="templates/index.html not found next to app.py",
                app_dir=_APP_DIR,
            ),
            500,
        )
    if not db_ok:
        return (
            jsonify(
                ok=False,
                database="error",
                templates="ok",
                detail=db_detail,
                app_dir=_APP_DIR,
            ),
            500,
        )
    # Deep check: actually render index.html (GET / can still 500 if Jinja fails on real data).
    if request.args.get("render") == "1":
        try:
            today = datetime.utcnow().date().isoformat()
            sample_lists = [{"id": 1, "name": "General", "sort_order": 0}]
            render_template(
                "index.html",
                todos=[],
                list_sections=[],
                view_mode="single",
                current_list="1",
                current_list_id=1,
                lists=sample_lists,
                has_tasks=False,
                now_date=today,
                deploy_enabled=False,
                completed_one_time=[],
                recurring_waiting=[],
            )
            render_template(
                "index.html",
                todos=[],
                list_sections=[
                    {
                        "id": 1,
                        "name": "General",
                        "todos": [],
                        "completed_one_time": [],
                        "recurring_waiting": [],
                    }
                ],
                view_mode="all",
                current_list="all",
                current_list_id=None,
                lists=sample_lists,
                has_tasks=False,
                now_date=today,
                deploy_enabled=False,
                completed_one_time=[],
                recurring_waiting=[],
            )
            return jsonify(ok=True, database="ok", templates="ok", render="ok")
        except Exception as e:
            app.logger.exception("health render self-test failed")
            return (
                jsonify(
                    ok=False,
                    render="failed",
                    detail=str(e),
                    app_dir=_APP_DIR,
                ),
                500,
            )
    return jsonify(ok=True, database="ok", templates="ok")


@app.route("/api/v1/todos", methods=["GET"])
@require_api_auth
def api_list_todos():
    db = get_db()
    _safe_process_overdue(db)
    now = datetime.utcnow().isoformat() + "Z"
    list_param = (request.args.get("list") or "").strip()
    if list_param == "all":
        sections = get_list_sections(db)
        return jsonify(
            server_time=now,
            mode="all",
            sections=[
                {
                    "id": s["id"],
                    "name": s["name"],
                    "todos": s["todos"],
                    "completed_one_time": s["completed_one_time"],
                    "recurring_waiting": s["recurring_waiting"],
                }
                for s in sections
            ],
        )
    if list_param:
        try:
            lid = int(list_param)
        except ValueError:
            lid = 1
        if not db.execute("SELECT 1 FROM lists WHERE id = ?", (lid,)).fetchone():
            lid = 1
        todos = build_todo_tree(db, list_id=lid)
        enrich_tree_subtask_stats(todos, build_subtask_stats(db, lid))
        return jsonify(
            server_time=now,
            mode="single",
            list_id=lid,
            todos=todos,
            completed_one_time=get_completed_one_time(db, lid),
            recurring_waiting=get_recurring_dormant(db, lid),
        )
    todos = build_todo_tree(db)
    enrich_tree_subtask_stats(todos, build_subtask_stats(db, None))
    return jsonify(
        server_time=now,
        mode="legacy",
        todos=todos,
        completed_one_time=[],
        recurring_waiting=[],
    )


@app.route("/api/v1/lists", methods=["GET"])
@require_api_auth
def api_get_lists():
    db = get_db()
    return jsonify(lists=get_all_lists(db))


@app.route("/api/v1/lists", methods=["POST"])
@require_api_auth
def api_post_list():
    data = request.get_json(force=True, silent=True) or {}
    name = (data.get("name") or "").strip()
    if not name:
        return jsonify(error="name required"), 400
    db = get_db()
    mx = db.execute("SELECT COALESCE(MAX(sort_order), 0) FROM lists").fetchone()[0]
    cur = db.execute(
        "INSERT INTO lists (name, sort_order) VALUES (?, ?)",
        (name, int(mx) + 1),
    )
    db.commit()
    return jsonify(ok=True, id=cur.lastrowid)


@app.route("/api/v1/todos", methods=["POST"])
@require_api_auth
def api_post_todo():
    data = request.get_json(force=True, silent=True) or {}
    title = (data.get("title") or "").strip()
    if not title:
        return jsonify(error="title required"), 400
    description = (data.get("description") or "").strip()
    parent_id = data.get("parent_id")
    recurrence = _normalize_recurrence(data.get("recurrence"))
    due_date = _normalize_due_date(data.get("due_date"))
    repeat_date = _normalize_due_date(data.get("repeat_date"))
    try:
        recurrence, due_date, repeat_date = _coerce_recurrence_dates(
            recurrence, due_date, repeat_date
        )
    except ValueError as e:
        return jsonify(error=str(e)), 400

    db = get_db()
    if parent_id is not None:
        parent_id = int(parent_id)
        prow = db.execute("SELECT list_id FROM todos WHERE id = ?", (parent_id,)).fetchone()
        list_id = int(prow["list_id"]) if prow and prow["list_id"] is not None else 1
    else:
        raw_lid = data.get("list_id") or 1
        try:
            list_id = int(raw_lid)
        except (TypeError, ValueError):
            list_id = 1
        if not db.execute("SELECT 1 FROM lists WHERE id = ?", (list_id,)).fetchone():
            list_id = 1

    cur = db.execute(
        """
        INSERT INTO todos (title, description, parent_id, recurrence, due_date, repeat_date, list_id)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        (title, description, parent_id, recurrence, due_date, repeat_date, list_id),
    )
    db.commit()
    return jsonify(ok=True, id=cur.lastrowid)


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


def _deploy_key_authorized():
    expected = os.environ.get("TODO_UPDATE_KEY")
    if not expected:
        return False
    got = (request.headers.get("X-Update-Key") or "").strip()
    if not got:
        got = (request.form.get("update_key") or "").strip()
    return got == expected


@app.route("/admin/update", methods=["POST"])
def admin_update():
    """Trigger git pull + install.sh + service restart (see deploy-lxc.sh). Requires TODO_UPDATE_KEY."""
    if not os.environ.get("TODO_UPDATE_KEY"):
        abort(404)
    if not _deploy_key_authorized():
        return jsonify(ok=False, error="unauthorized"), 401
    repo = (os.environ.get("TODO_UPDATE_REPO_DIR") or "/tmp/todo-manager").strip() or "/tmp/todo-manager"
    if not os.path.isfile(_DEPLOY_SCRIPT):
        app.logger.error("todo-manager-deploy missing at %s", _DEPLOY_SCRIPT)
        return (
            jsonify(
                ok=False,
                error="Deploy script not installed. Re-run install.sh on the server.",
            ),
            503,
        )
    try:
        subprocess.Popen(
            ["sudo", "-n", _DEPLOY_SCRIPT, repo],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
            cwd="/",
        )
    except FileNotFoundError:
        return jsonify(ok=False, error="sudo not found"), 503
    except OSError as e:
        app.logger.exception("deploy trigger failed")
        return jsonify(ok=False, error=str(e)), 500
    app.logger.info("Server update triggered (repo=%s)", repo)
    return jsonify(
        ok=True,
        message="Update started. Git pull and install run in the background; the service will restart shortly.",
    )


@app.route("/")
def index():
    db = get_db()
    _safe_process_overdue(db)
    today = datetime.utcnow().date().isoformat()
    list_param = (request.args.get("list") or "1").strip()
    all_lists = get_all_lists(db)

    if list_param == "all":
        sections = get_list_sections(db)
        has_tasks = any(
            len(s["todos"]) > 0
            or len(s["completed_one_time"]) > 0
            or len(s["recurring_waiting"]) > 0
            for s in sections
        )
        return render_template(
            "index.html",
            todos=[],
            list_sections=sections,
            view_mode="all",
            current_list="all",
            current_list_id=None,
            lists=all_lists,
            has_tasks=has_tasks,
            now_date=today,
            deploy_enabled=bool(os.environ.get("TODO_UPDATE_KEY")),
            completed_one_time=[],
            recurring_waiting=[],
        )

    try:
        list_id = int(list_param)
    except ValueError:
        list_id = 1
    exists = db.execute("SELECT 1 FROM lists WHERE id = ?", (list_id,)).fetchone()
    if not exists:
        list_id = 1

    todos = build_todo_tree(db, list_id=list_id)
    enrich_tree_subtask_stats(todos, build_subtask_stats(db, list_id))
    completed_one_time = get_completed_one_time(db, list_id)
    recurring_waiting = get_recurring_dormant(db, list_id)
    has_tasks = bool(todos) or bool(completed_one_time) or bool(recurring_waiting)
    return render_template(
        "index.html",
        todos=todos,
        list_sections=[],
        view_mode="single",
        current_list=str(list_id),
        current_list_id=list_id,
        lists=all_lists,
        has_tasks=has_tasks,
        now_date=today,
        deploy_enabled=bool(os.environ.get("TODO_UPDATE_KEY")),
        completed_one_time=completed_one_time,
        recurring_waiting=recurring_waiting,
    )


def process_overdue_recurrences(db):
    """Reopen dormant recurring tasks when repeat_date is reached (completed until then)."""
    try:
        today = datetime.utcnow().date().isoformat()
        overdue = db.execute(
            """
            SELECT * FROM todos
            WHERE recurrence IS NOT NULL
              AND repeat_date IS NOT NULL
              AND repeat_date <= ?
              AND completed = 1
            """,
            (today,),
        ).fetchall()
        for todo in overdue:
            try:
                db.execute(
                    "UPDATE todos SET repeat_date = NULL WHERE id = ?",
                    (todo["id"],),
                )
                uncomplete_recursive(db, todo["id"])
            except (TypeError, ValueError):
                continue
        db.commit()
    except sqlite3.Error:
        app.logger.exception("process_overdue_recurrences failed")
        try:
            db.rollback()
        except sqlite3.Error:
            pass


def _safe_process_overdue(db):
    """Never let overdue maintenance take down a request."""
    try:
        process_overdue_recurrences(db)
    except Exception:
        app.logger.exception("process_overdue_recurrences unexpected error")


@app.route("/add", methods=["POST"])
def add_todo():
    title = request.form.get("title", "").strip()
    if not title:
        return redirect_to_index()

    description = request.form.get("description", "").strip()
    parent_id = request.form.get("parent_id") or None
    recurrence = _normalize_recurrence(request.form.get("recurrence"))
    due_date = _normalize_due_date(request.form.get("due_date"))
    repeat_date = _normalize_due_date(request.form.get("repeat_date"))
    try:
        recurrence, due_date, repeat_date = _coerce_recurrence_dates(
            recurrence, due_date, repeat_date
        )
    except ValueError:
        return redirect_to_index()

    db = get_db()
    if parent_id:
        parent_id = int(parent_id)
        prow = db.execute("SELECT list_id FROM todos WHERE id = ?", (parent_id,)).fetchone()
        list_id = int(prow["list_id"]) if prow and prow["list_id"] is not None else 1
    else:
        raw_lid = request.form.get("list_id") or request.form.get("target_list_id") or "1"
        try:
            list_id = int(raw_lid)
        except ValueError:
            list_id = 1
        if not db.execute("SELECT 1 FROM lists WHERE id = ?", (list_id,)).fetchone():
            list_id = 1

    cur = db.execute(
        """
        INSERT INTO todos (title, description, parent_id, recurrence, due_date, repeat_date, list_id)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        (title, description, parent_id, recurrence, due_date, repeat_date, list_id),
    )
    db.commit()
    new_id = cur.lastrowid
    if (
        request.headers.get("X-Requested-With") == "XMLHttpRequest"
        and parent_id
    ):
        return jsonify(ok=True, id=new_id)
    return redirect_to_index()


@app.route("/toggle/<int:todo_id>", methods=["POST"])
def toggle_todo(todo_id):
    db = get_db()
    todo = db.execute("SELECT * FROM todos WHERE id = ?", (todo_id,)).fetchone()
    if not todo:
        return redirect_to_index()

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

    return redirect_to_index()


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
        return redirect_to_index()

    description = request.form.get("description", "").strip()
    recurrence = _normalize_recurrence(request.form.get("recurrence"))
    due_date = _normalize_due_date(request.form.get("due_date"))
    repeat_date = _normalize_due_date(request.form.get("repeat_date"))
    try:
        recurrence, due_date, repeat_date = _coerce_recurrence_dates(
            recurrence, due_date, repeat_date
        )
    except ValueError:
        return redirect_to_index()

    todo = db.execute("SELECT * FROM todos WHERE id = ?", (todo_id,)).fetchone()
    if not todo:
        return redirect_to_index()

    db.execute(
        """
        UPDATE todos SET title = ?, description = ?, recurrence = ?, due_date = ?, repeat_date = ?
        WHERE id = ?
        """,
        (title, description, recurrence, due_date, repeat_date, todo_id),
    )
    db.commit()
    return redirect_to_index()


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

    return redirect_to_index()


@app.route("/lists/add", methods=["POST"])
def add_list():
    name = request.form.get("name", "").strip()
    if not name:
        return redirect_to_index()
    db = get_db()
    mx = db.execute("SELECT COALESCE(MAX(sort_order), 0) FROM lists").fetchone()[0]
    db.execute(
        "INSERT INTO lists (name, sort_order) VALUES (?, ?)",
        (name, int(mx) + 1),
    )
    db.commit()
    return redirect_to_index()


if __name__ == "__main__":
    app.run(debug=True, host="0.0.0.0", port=5000)
