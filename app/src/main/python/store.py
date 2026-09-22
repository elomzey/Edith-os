"""Mémoire persistante, compteurs de tokens et journal (SQLite, bibliothèque standard)."""
import os
import sqlite3
import threading
import time

_lock = threading.Lock()
_path = None


def _run(sql, params=(), fetch=False, script=False):
    with _lock:
        conn = sqlite3.connect(_path, timeout=10)
        try:
            rows = []
            if script:
                conn.executescript(sql)
            else:
                cur = conn.execute(sql, params)
                if fetch:
                    rows = cur.fetchall()
            conn.commit()
            return rows
        finally:
            conn.close()


def init(files_dir):
    global _path
    _path = os.path.join(files_dir, "edith.db")
    _run(
        """
        CREATE TABLE IF NOT EXISTS messages(
            id INTEGER PRIMARY KEY AUTOINCREMENT, ts REAL, role TEXT, content TEXT);
        CREATE TABLE IF NOT EXISTS usage(
            day TEXT, provider TEXT, model TEXT,
            requests INTEGER DEFAULT 0, prompt_tokens INTEGER DEFAULT 0,
            completion_tokens INTEGER DEFAULT 0,
            PRIMARY KEY(day, provider, model));
        CREATE TABLE IF NOT EXISTS quota(
            provider TEXT, model TEXT, remaining_tokens TEXT, remaining_requests TEXT,
            reset TEXT, ts REAL, PRIMARY KEY(provider, model));
        CREATE TABLE IF NOT EXISTS events(
            id INTEGER PRIMARY KEY AUTOINCREMENT, ts REAL, kind TEXT, detail TEXT);
        CREATE TABLE IF NOT EXISTS kv(key TEXT PRIMARY KEY, value TEXT);
        """,
        script=True,
    )


# ---------- Conversations ----------

def add_message(role, content):
    _run("INSERT INTO messages(ts, role, content) VALUES(?,?,?)", (time.time(), role, content))


def get_messages(limit=30):
    rows = _run("SELECT role, content FROM messages ORDER BY id DESC LIMIT ?", (int(limit),), fetch=True)
    return [{"role": r, "content": c} for r, c in reversed(rows)]


def clear_messages():
    _run("DELETE FROM messages")


# ---------- Consommation de tokens ----------

def record_usage(provider, model, prompt_tokens, completion_tokens):
    day = time.strftime("%Y-%m-%d")
    _run(
        """INSERT INTO usage(day, provider, model, requests, prompt_tokens, completion_tokens)
           VALUES(?,?,?,1,?,?)
           ON CONFLICT(day, provider, model) DO UPDATE SET
             requests = requests + 1,
             prompt_tokens = prompt_tokens + excluded.prompt_tokens,
             completion_tokens = completion_tokens + excluded.completion_tokens""",
        (day, provider, model, int(prompt_tokens or 0), int(completion_tokens or 0)),
    )


def record_quota(provider, model, headers):
    """Mémorise les quotas restants quand l'API les annonce dans ses en-têtes."""
    rt = headers.get("x-ratelimit-remaining-tokens")
    rr = headers.get("x-ratelimit-remaining-requests")
    if rt is None and rr is None:
        return
    reset = headers.get("x-ratelimit-reset-tokens") or headers.get("x-ratelimit-reset-requests")
    _run(
        "INSERT OR REPLACE INTO quota(provider, model, remaining_tokens, remaining_requests, reset, ts)"
        " VALUES(?,?,?,?,?,?)",
        (provider, model, rt, rr, reset, time.time()),
    )


# ---------- Journal ----------

def log_event(kind, detail):
    _run("INSERT INTO events(ts, kind, detail) VALUES(?,?,?)", (time.time(), kind, detail))
    _run("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT 200)")


def summary():
    today = time.strftime("%Y-%m-%d")
    usage = _run(
        """SELECT provider, model, SUM(requests), SUM(prompt_tokens), SUM(completion_tokens),
                  SUM(CASE WHEN day = ? THEN prompt_tokens + completion_tokens ELSE 0 END)
           FROM usage GROUP BY provider, model ORDER BY provider, model""",
        (today,), fetch=True)
    quota = _run(
        "SELECT provider, model, remaining_tokens, remaining_requests, reset, ts FROM quota", fetch=True)
    events = _run("SELECT ts, kind, detail FROM events ORDER BY id DESC LIMIT 30", fetch=True)
    return {
        "usage": [
            {"provider": r[0], "model": r[1], "requests": r[2], "prompt_tokens": r[3],
             "completion_tokens": r[4], "today_tokens": r[5]}
            for r in usage
        ],
        "quota": [
            {"provider": r[0], "model": r[1], "remaining_tokens": r[2],
             "remaining_requests": r[3], "reset": r[4], "ts": r[5]}
            for r in quota
        ],
        "events": [{"ts": r[0], "kind": r[1], "detail": r[2]} for r in events],
    }


# ---------- Configuration simple (clé/valeur) : agence d'agents, etc. ----------

def get_kv(key, default=None):
    rows = _run("SELECT value FROM kv WHERE key=?", (key,), fetch=True)
    return rows[0][0] if rows else default


def set_kv(key, value):
    _run(
        "INSERT INTO kv(key, value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        (key, value),
    )
