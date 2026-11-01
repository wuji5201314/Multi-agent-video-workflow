-- Multi-agent-video-workflow 管线库（SQLite，运行时自动建表，这里作为可读快照）
-- 复用点：steps.payload 的结构与 yq-admin aimedia 的 preview_plan_json 同构，
-- 迁移到 MySQL 时把 INTEGER PRIMARY KEY AUTOINCREMENT 换成 BIGINT AUTO_INCREMENT 即可。

CREATE TABLE IF NOT EXISTS tasks (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    brief        TEXT    NOT NULL,
    style        TEXT    NOT NULL DEFAULT '',
    status       TEXT    NOT NULL,          -- WAITING_INPUT / RUNNING / DONE / FAILED
    current_step TEXT    NOT NULL,
    created_at   TEXT    NOT NULL,
    updated_at   TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS steps (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id     INTEGER NOT NULL,
    code        TEXT    NOT NULL,            -- TOPIC / SCRIPT / MATERIAL / RENDER / PUBLISH
    order_no    INTEGER NOT NULL,
    status      TEXT    NOT NULL,            -- PENDING / WAITING_INPUT / RUNNING / SUCCEEDED / FAILED
    payload     TEXT    NOT NULL DEFAULT '{}',
    feedback    TEXT    NOT NULL DEFAULT '',
    error       TEXT    NOT NULL DEFAULT '',
    updated_at  TEXT    NOT NULL,
    UNIQUE (task_id, code)
);

CREATE TABLE IF NOT EXISTS gate_log (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id    INTEGER NOT NULL,
    step_code  TEXT    NOT NULL,
    decision   TEXT    NOT NULL,             -- approve / edit / reject
    note       TEXT    NOT NULL DEFAULT '',
    payload    TEXT    NOT NULL DEFAULT '{}',
    created_at TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS artifacts (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id    INTEGER NOT NULL,
    step_code  TEXT    NOT NULL,
    kind       TEXT    NOT NULL,             -- video / cover / material
    path       TEXT    NOT NULL,
    size       INTEGER NOT NULL DEFAULT 0,
    created_at TEXT    NOT NULL
);
