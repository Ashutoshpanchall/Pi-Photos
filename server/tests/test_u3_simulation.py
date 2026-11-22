"""Phase U3: End-to-end quota threshold simulation test against real FastAPI server."""

import os
import sys
import tempfile
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent))

import collections
from fastapi.testclient import TestClient
from unittest.mock import patch
from main import app, ip_limiter, global_pairing_limiter
import hashlib

DiskUsage = collections.namedtuple("usage", "total used free")


class TestU3Simulation:

    def setup_method(self):
        self.tmp = tempfile.mkdtemp()
        os.environ["PIPHOTOS_STORAGE"] = os.path.join(self.tmp, "storage")
        os.environ["PIPHOTOS_DB"] = os.path.join(self.tmp, "test.db")
        os.environ["PIPHOTOS_CERTS"] = os.path.join(self.tmp, "certs")
        os.environ["PIPHOTOS_CONFIG"] = os.path.join(self.tmp, "nonexistent.json")
        os.environ["PIPHOTOS_NO_MDNS"] = "1"
        os.environ["PIPHOTOS_NO_QR"] = "1"
        os.environ["PIPHOTOS_DISK_RESERVE_FRACTION"] = "0.0"

        ip_limiter._requests.clear()
        global_pairing_limiter.reset()

        self._tc = TestClient(app)
        self.client = self._tc.__enter__()

    def teardown_method(self):
        self._tc.__exit__(None, None, None)

    @patch("shutil.disk_usage", return_value=DiskUsage(100 * 1024**3, 10 * 1024**3, 90 * 1024**3))
    def test_u3_quota_thresholds_pause_and_resume(self, _mock_disk):
        from main import pairing, db, storage
        # 1. Pair owner
        code = pairing.generate_code()
        pair_resp = self.client.post("/api/v1/pair", json={
            "code": code,
            "device_name": "Owner Phone",
            "device_model": "Pixel 9",
        })
        assert pair_resp.status_code == 200
        owner_token = pair_resp.json()["token"]
        owner_headers = {"Authorization": f"Bearer {owner_token}"}

        # 2. Owner creates member with 10 MB quota
        member_quota = 10 * 1024 * 1024  # 10 MB
        res = self.client.post(
            "/api/v1/admin/users",
            headers=owner_headers,
            json={"display_name": "Bob", "quota_bytes": member_quota},
        )
        assert res.status_code == 200
        member_data = res.json()
        member_id = member_data["user"]["id"]
        invite_code = member_data["invite_code"]

        # 3. Member pairs with invite code
        m_pair = self.client.post(
            "/api/v1/pair",
            json={"code": invite_code, "device_name": "Bob Phone", "device_model": "Galaxy S24"},
        )
        assert m_pair.status_code == 200
        member_token = m_pair.json()["token"]
        member_dev_id = m_pair.json()["device_id"]
        member_headers = {"Authorization": f"Bearer {member_token}"}

        # Check initial status: 0 used, free 10 MB
        st = self.client.get("/api/v1/status", headers=member_headers).json()
        assert st["user"]["quota_bytes"] == member_quota
        assert st["user"]["used_bytes"] == 0
        assert st["user"]["soft_quota_warning"] is None

        # Step A: Upload 7.5 MB (7,864,320 bytes) -> 75% (< 80% OK)
        payload_7_5mb = b"A" * 7864320
        h1 = hashlib.sha256(payload_7_5mb).hexdigest()
        up1 = self.client.put(
            f"/api/v1/upload/{h1}",
            headers={
                **member_headers,
                "Content-Length": str(len(payload_7_5mb)),
                "Upload-Offset": "0",
            },
            params={
                "device": member_dev_id,
                "folder": "Camera",
                "name": "photo1.jpg",
                "date_taken": "1732000000",
            },
            content=payload_7_5mb,
        )
        assert up1.status_code == 200

        st = self.client.get("/api/v1/status", headers=member_headers).json()
        assert st["user"]["used_bytes"] == len(payload_7_5mb)
        assert st["user"]["soft_quota_warning"] is None

        # Step B: Upload 1.0 MB (1,048,576 bytes) -> Total 8.5 MB (85%) -> Warning 80%
        payload_1mb = b"B" * 1048576
        h2 = hashlib.sha256(payload_1mb).hexdigest()
        up2 = self.client.put(
            f"/api/v1/upload/{h2}",
            headers={
                **member_headers,
                "Content-Length": str(len(payload_1mb)),
                "Upload-Offset": "0",
            },
            params={
                "device": member_dev_id,
                "folder": "Camera",
                "name": "photo2.jpg",
                "date_taken": "1732000001",
            },
            content=payload_1mb,
        )
        assert up2.status_code == 200

        st = self.client.get("/api/v1/status", headers=member_headers).json()
        assert st["user"]["soft_quota_warning"] == "80%"

        # Step C: Upload 0.6 MB (629,146 bytes) -> Total 9.1 MB (91%) -> Warning 90%
        payload_0_6mb = b"C" * 629146
        h3 = hashlib.sha256(payload_0_6mb).hexdigest()
        up3 = self.client.put(
            f"/api/v1/upload/{h3}",
            headers={
                **member_headers,
                "Content-Length": str(len(payload_0_6mb)),
                "Upload-Offset": "0",
            },
            params={
                "device": member_dev_id,
                "folder": "Camera",
                "name": "photo3.jpg",
                "date_taken": "1732000002",
            },
            content=payload_0_6mb,
        )
        assert up3.status_code == 200

        st = self.client.get("/api/v1/status", headers=member_headers).json()
        assert st["user"]["soft_quota_warning"] == "90%"

        # Step D: Attempt 1.5 MB upload -> Total 10.6 MB > 10 MB quota -> 507 Quota Exceeded!
        payload_1_5mb = b"D" * 1572864
        h4 = hashlib.sha256(payload_1_5mb).hexdigest()
        up4 = self.client.put(
            f"/api/v1/upload/{h4}",
            headers={
                **member_headers,
                "Content-Length": str(len(payload_1_5mb)),
                "Upload-Offset": "0",
            },
            params={
                "device": member_dev_id,
                "folder": "Camera",
                "name": "photo4.jpg",
                "date_taken": "1732000003",
            },
            content=payload_1_5mb,
        )
        assert up4.status_code == 507
        assert up4.json()["detail"]["error"] == "quota_exceeded"

        # Step E: Owner raises quota to 20 MB
        new_quota = 20 * 1024 * 1024
        patch_res = self.client.patch(
            f"/api/v1/admin/users/{member_id}",
            headers=owner_headers,
            json={"quota_bytes": new_quota},
        )
        assert patch_res.status_code == 200
        assert patch_res.json()["quota_bytes"] == new_quota

        # Step F: Status reflects new quota and clears warning
        st_after = self.client.get("/api/v1/status", headers=member_headers).json()
        assert st_after["user"]["quota_bytes"] == new_quota
        assert st_after["user"]["soft_quota_warning"] is None
        assert st_after["user"]["free_bytes"] > len(payload_1_5mb)

        # Step G: Upload resumes and completes successfully without user intervention
        up4_resumed = self.client.put(
            f"/api/v1/upload/{h4}",
            headers={
                **member_headers,
                "Content-Length": str(len(payload_1_5mb)),
                "Upload-Offset": "0",
            },
            params={
                "device": member_dev_id,
                "folder": "Camera",
                "name": "photo4.jpg",
                "date_taken": "1732000003",
            },
            content=payload_1_5mb,
        )
        assert up4_resumed.status_code == 200

        # Verify all 4 files are listed under member
        files = self.client.get("/api/v1/files", headers=member_headers).json()
        assert len(files) == 4
