"""Tests for OpenAPI schema export and CI drift check (Improvement 7)."""

from __future__ import annotations

import json
from pathlib import Path
import pytest

import main


class TestOpenApiDrift:
    """Improvement 7: OpenAPI Client Generation & CI Check."""

    def test_openapi_schema_is_committed_and_in_sync(self):
        schema_path = Path(__file__).resolve().parent.parent / "openapi.json"
        assert schema_path.is_file(), (
            "server/openapi.json is missing! Generate it using python server/scripts/export_openapi.py"
        )

        committed_schema = json.loads(schema_path.read_text(encoding="utf-8"))
        live_schema = main.app.openapi()

        # Both must match exactly (ignoring key order)
        assert committed_schema == live_schema, (
            "server/openapi.json is out of date with current FastAPI routes/models! "
            "Run 'python server/scripts/export_openapi.py' to update schema."
        )

    def test_required_client_endpoints_present_in_schema(self):
        schema = main.app.openapi()
        paths = schema.get("paths", {})

        required_endpoints = [
            ("/api/v1/info", "get"),
            ("/api/v1/pair", "post"),
            ("/api/v1/status", "get"),
            ("/api/v1/upload/check", "post"),
            ("/api/v1/upload/{sha256}", "head"),
            ("/api/v1/upload/{sha256}", "put"),
            ("/api/v1/me", "get"),
            ("/api/v1/files", "get"),
            ("/api/v1/files/{file_id}/content", "get"),
            ("/api/v1/devices/{device_id}/folders", "get"),
            ("/api/v1/admin/overview", "get"),
            ("/api/v1/admin/users", "get"),
            ("/api/v1/admin/users", "post"),
            ("/api/v1/admin/users/{user_id}", "patch"),
            ("/api/v1/admin/users/{user_id}", "delete"),
            ("/api/v1/admin/users/{user_id}/restore", "post"),
            ("/api/v1/admin/users/{user_id}/devices", "get"),
            ("/api/v1/admin/users/{user_id}/devices/{device_id}", "patch"),
            ("/api/v1/admin/pairing/open", "post"),
            ("/api/v1/admin/storage/allocation", "post"),
        ]

        for path, method in required_endpoints:
            assert path in paths, f"Missing endpoint {path} in OpenAPI schema"
            assert method in paths[path], f"Missing method {method.upper()} on endpoint {path}"

    def test_models_contain_required_phase4_and_phase5_fields(self):
        schema = main.app.openapi()
        schemas = schema.get("components", {}).get("schemas", {})

        # AdminUserSummary should have disabled_at and scheduled_purge_at
        user_summary = schemas.get("AdminUserSummary", {}).get("properties", {})
        assert "disabled_at" in user_summary, "AdminUserSummary missing disabled_at"
        assert "scheduled_purge_at" in user_summary, "AdminUserSummary missing scheduled_purge_at"

        # DeviceSummary should have role
        dev_summary = schemas.get("DeviceSummary", {}).get("properties", {})
        assert "role" in dev_summary, "DeviceSummary missing role"

        # MeResponse should have device_role
        me_response = schemas.get("MeResponse", {}).get("properties", {})
        assert "device_role" in me_response, "MeResponse missing device_role"
