import io
import json
import os
import unittest

import main


class FakeStore:
    def __init__(self) -> None:
        self.bucket_name = "droidlm-debug-logs"
        self.object_names = []
        self.writes = []

    def object_name_for(self, filename):
        self.object_names.append(filename)
        return "debug-logs/synthetic/bundle.zip"

    def write_object(self, object_name, data, content_type, metadata):
        self.writes.append(
            {
                "object_name": object_name,
                "data": data,
                "content_type": content_type,
                "metadata": metadata,
            }
        )

    def signed_upload_url(self, object_name, content_type, expires_seconds):
        return f"https://storage.example.test/upload/{object_name}?expires={expires_seconds}&contentType={content_type}"


class FakeFile:
    def __init__(self, filename, content_type, data):
        self.filename = filename
        self.content_type = content_type
        self.stream = io.BytesIO(data)


class FakeRequest:
    def __init__(self, method, path="/", files=None, form=None, headers=None, json_data=None):
        self.method = method
        self.path = path
        self.files = files or {}
        self.form = form or {}
        self.headers = headers or {}
        self._json_data = json_data

    def get_json(self, silent=False):
        return self._json_data


class DebugLogFunctionTest(unittest.TestCase):
    def setUp(self):
        self.original_store = main._debug_log_store
        self.original_max = os.environ.get("DROIDLM_MAX_DEBUG_LOG_BYTES")
        self.original_allowlist = main.require_allowlisted_request
        main._debug_log_store = None
        os.environ["DROIDLM_MAX_DEBUG_LOG_BYTES"] = "1024"
        main.require_allowlisted_request = lambda _request: {"email": "allowed@example.com", "email_verified": True}

    def tearDown(self):
        main._debug_log_store = self.original_store
        main.require_allowlisted_request = self.original_allowlist
        if self.original_max is None:
            os.environ.pop("DROIDLM_MAX_DEBUG_LOG_BYTES", None)
        else:
            os.environ["DROIDLM_MAX_DEBUG_LOG_BYTES"] = self.original_max

    def test_handle_debug_log_upload_matches_android_contract(self):
        store = FakeStore()
        payload = main.handle_debug_log_upload(
            filename="bundle.zip",
            content_type="application/zip",
            data=b"abc",
            app_package="com.studionext54.droidlm.debug",
            app_version="0.1-debug",
            store=store,
        )

        self.assertEqual("debug-logs/synthetic/bundle.zip", payload["objectName"])
        self.assertEqual("gs://droidlm-debug-logs/debug-logs/synthetic/bundle.zip", payload["gsUri"])
        self.assertEqual(3, payload["sizeBytes"])
        self.assertEqual("application/zip", store.writes[0]["content_type"])
        self.assertEqual("com.studionext54.droidlm.debug", store.writes[0]["metadata"]["appPackage"])
        self.assertEqual("0.1-debug", store.writes[0]["metadata"]["appVersion"])

    def test_entrypoint_accepts_root_post(self):
        store = FakeStore()
        main._debug_log_store = store
        request = FakeRequest(
            method="POST",
            path="/",
            files={"logs": FakeFile("bundle.zip", "application/zip", b"zip")},
            form={"appPackage": "com.studionext54.droidlm.debug", "appVersion": "0.1-debug"},
        )

        body, status_code, headers = main.droidlm_debug_log_upload(request)

        self.assertEqual(200, status_code)
        self.assertEqual("application/json", headers["Content-Type"])
        payload = json.loads(body)
        self.assertTrue(payload["ok"])
        self.assertEqual("debug-logs/synthetic/bundle.zip", payload["objectName"])


    def test_entrypoint_creates_signed_upload_session(self):
        store = FakeStore()
        main._debug_log_store = store
        request = FakeRequest(
            method="POST",
            path="/debug-logs/upload-session",
            json_data={
                "filename": "bundle.zip",
                "contentType": "application/zip",
                "sizeBytes": 3,
                "appPackage": "com.droidlm.debug",
                "appVersion": "0.1-debug",
            },
        )

        body, status_code, headers = main.droidlm_debug_log_upload(request)

        self.assertEqual(200, status_code)
        self.assertEqual("application/json", headers["Content-Type"])
        payload = json.loads(body)
        self.assertEqual("debug-logs/synthetic/bundle.zip", payload["objectName"])
        self.assertEqual("gs://droidlm-debug-logs/debug-logs/synthetic/bundle.zip", payload["gsUri"])
        self.assertEqual("PUT", payload["uploadMethod"])
        self.assertEqual("application/zip", payload["uploadHeaders"]["Content-Type"])
        self.assertIn("https://storage.example.test/upload/", payload["uploadUrl"])
        self.assertEqual([], store.writes)

    def test_custom_firebase_header_is_accepted(self):
        request = FakeRequest(
            method="POST",
            headers={"X-DroidLM-Firebase-ID-Token": "custom-token"},
        )

        self.assertEqual("custom-token", main.bearer_token(main.auth_header_value(request)))
    def test_health_endpoint_works(self):
        body, status_code, headers = main.droidlm_debug_log_upload(FakeRequest(method="GET", path="/health"))

        self.assertEqual(200, status_code)
        self.assertEqual("application/json", headers["Content-Type"])
        self.assertEqual({"ok": True}, json.loads(body))

    def test_upload_requires_firebase_bearer(self):
        main.require_allowlisted_request = self.original_allowlist
        request = FakeRequest(
            method="POST",
            path="/debug-logs",
            files={"logs": FakeFile("bundle.zip", "application/zip", b"zip")},
        )

        body, status_code, _ = main.droidlm_debug_log_upload(request)

        self.assertEqual(401, status_code)
        payload = json.loads(body)
        self.assertEqual("AUTH_TOKEN_MISSING", payload["errorCode"])


    def test_upload_rejects_large_payload(self):
        request = FakeRequest(
            method="POST",
            path="/debug-logs",
            files={"logs": FakeFile("bundle.zip", "application/zip", b"x" * 1025)},
        )

        body, status_code, _ = main.droidlm_debug_log_upload(request)

        self.assertEqual(413, status_code)
        payload = json.loads(body)
        self.assertEqual("DEBUG_LOG_TOO_LARGE", payload["errorCode"])

    def test_safe_filename_sanitizes_input(self):
        self.assertEqual("hello-world.zip", main.safe_filename(" ../hello world "))


if __name__ == "__main__":
    unittest.main()
