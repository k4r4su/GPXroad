"""Tests du serveur des points bloqués (base temporaire, jamais la vraie). Lancer : `pytest server/test_app.py`."""
import sqlite3
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

import app as server


@pytest.fixture()
def client(tmp_path, monkeypatch):
    monkeypatch.setattr(server, "DB_PATH", tmp_path / "test.db")
    server.init_db()
    return TestClient(server.app)


def report(client, **fields):
    body = {"lat": 47.5, "lon": 7.5, "reporter_id": "a1b2", **fields}
    return client.post("/blockages", json=body)


def test_old_clients_still_work_and_get_kind_blocked(client):
    response = report(client, note="arbre au sol")
    assert response.status_code == 200
    assert response.json()["kind"] == "blocked"
    assert response.json()["way_id"] is None


def test_forbidden_report_keeps_its_kind_and_way(client):
    response = report(client, kind="forbidden", way_id=50316888, note="barrière")
    assert response.json()["kind"] == "forbidden"
    assert response.json()["way_id"] == 50316888


def test_a_blocked_point_and_a_forbidden_point_never_merge(client):
    first = report(client).json()
    second = report(client, kind="forbidden").json()
    assert first["id"] != second["id"], "même endroit, types différents : deux points"


def test_same_kind_within_100_m_is_reconfirmed_not_duplicated(client):
    first = report(client, kind="forbidden").json()
    again = report(client, kind="forbidden", lat=47.5003).json()   # ~33 m
    assert again["id"] == first["id"]
    assert len(client.get("/blockages", params={"min_lat": 47, "min_lon": 7, "max_lat": 48, "max_lon": 8}).json()) == 1


def test_same_osm_way_is_reconfirmed_even_far_apart(client):
    first = report(client, kind="forbidden", way_id=42).json()
    again = report(client, kind="forbidden", way_id=42, lat=47.52).json()   # ~2 km, même chemin
    assert again["id"] == first["id"]


def test_list_can_be_filtered_by_kind(client):
    report(client)
    report(client, kind="forbidden", lat=47.6)
    box = {"min_lat": 47, "min_lon": 7, "max_lat": 48, "max_lon": 8}
    assert len(client.get("/blockages", params=box).json()) == 2
    only = client.get("/blockages", params={**box, "kind": "forbidden"}).json()
    assert [b["kind"] for b in only] == ["forbidden"]


def test_unknown_kind_is_rejected(client):
    assert report(client, kind="n_importe_quoi").status_code == 422


def test_existing_database_is_migrated_without_losing_points(tmp_path, monkeypatch):
    path = tmp_path / "old.db"
    conn = sqlite3.connect(path)
    conn.execute("CREATE TABLE blockages (id TEXT PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL, note TEXT, reporter_id TEXT NOT NULL, created_at TEXT NOT NULL, last_confirmed_at TEXT NOT NULL)")
    now = server.now_iso()
    conn.execute("INSERT INTO blockages VALUES ('old', 47.5, 7.5, 'ancien', 'r', ?, ?)", (now, now))
    conn.commit()
    conn.close()
    monkeypatch.setattr(server, "DB_PATH", path)
    server.init_db()
    rows = TestClient(server.app).get("/blockages", params={"min_lat": 47, "min_lon": 7, "max_lat": 48, "max_lon": 8}).json()
    assert [(r["id"], r["kind"]) for r in rows] == [("old", "blocked")]
