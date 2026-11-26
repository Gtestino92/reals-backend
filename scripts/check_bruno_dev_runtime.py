"""Run the collection scripts with Bruno 4.3.0 against loopback-only synthetic services."""
import base64
import json
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path


def jwt(claims):
    def encode(value):
        return base64.urlsafe_b64encode(json.dumps(value).encode()).decode().rstrip("=")
    return encode({"alg": "HS256"}) + "." + encode(claims) + ".synthetic"


folder = Path("bruno/16 - AWS Dev Tooling")
claims = dict(exp=int(time.time()) + 600, email="test@example.org", email_verified=True)
identity = jwt(dict(claims, sub="fixture", firebase={"sign_in_provider": "password"}))
app = jwt(dict(exp=claims["exp"]))
grant = "G" * 43
seen = []


class Fixture(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path == "/api/local-dev/user-reliability/fixture":
            assert self.headers["Authorization"] == "Bearer " + identity
            assert self.headers["X-Firebase-AppCheck"] == app and self.headers["X-Reals-Admin-Grant"] == grant
            seen.append(self.path)
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"userId":"fixture","enabled":true,"events":[]}')
            return
        assert self.path == "/api/ping"
        assert not self.headers.get("Authorization") and not self.headers.get("X-Reals-Admin-Grant")
        self.send_response(204)
        self.end_headers()

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        if self.path == "/app":
            assert json.loads(body)["debugToken"] == "synthetic"
            result = {"token": app}
        elif self.path == "/login?key=mock":
            assert json.loads(body) == {"email": claims["email"], "password": "synthetic", "returnSecureToken": True}
            result = {"idToken": identity}
        else:
            assert self.headers["Authorization"] == "Bearer " + identity
            assert self.headers["X-Firebase-AppCheck"] == app
            if self.path.endswith("/verify"):
                result = {"grant": grant, "expiresAt": "2099-01-01T00:00:00Z"}
            else:
                assert self.path.endswith("/revoke") and self.headers["X-Reals-Admin-Grant"] == grant
                result = None
        seen.append(self.path)
        self.send_response(204 if result is None else 200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        if result is not None:
            self.wfile.write(json.dumps(result).encode())


with tempfile.TemporaryDirectory() as tmp, HTTPServer(("127.0.0.1", 0), Fixture) as server:
    root = Path(tmp)
    url = "http://127.0.0.1:" + str(server.server_port)
    shutil.copy("bruno/bruno.json", root)
    suite = root / "suite"
    suite.mkdir()
    shutil.copy(folder / "folder.bru", suite)
    (root / "environments").mkdir()
    (root / "environments/dev.bru").write_text("""vars {
  baseUrl: https://dev.example
  devUserId: fixture
  firebase_admin_password: synthetic
  firebase_app_check_debug_token: synthetic
  firebase_api_key: mock
  firebase_project_number: 123
  firebase_admin_web_app_id: 1:123:web:abc
  firebase_admin_expected_uid: fixture
  firebase_admin_email: test@example.org
}
""", encoding="utf8")
    for seq, number in enumerate(["00b", "00", "06", "60", "99", "98"], start=1):
        path = next(folder.glob(number + "-*.bru"))
        source = path.read_text(encoding="utf8")
        source = re.sub(r"url: https://firebaseappcheck[^\n]+", "url: " + url + "/app", source)
        source = source.replace("{{baseUrl}}/api", url + "/api")
        source = source.replace("https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword", url + "/login")
        source = re.sub(r"{{\?[^}]+}}", "synthetic", source)
        source = re.sub(r"  seq: \d+", "  seq: " + str(seq), source)
        if number == "60":
            source += '\ntests {\n  bru.setVar("devAdminGrantExpiry", "2000-01-01");\n}\n'
        if number == "99":
            source += '\ntests {\n  test("revocation cleared the grant", () => expect(bru.getVar("devAdminGrant")).to.not.be.ok);\n  bru.setVar("devAdminIdToken", "expired");\n  bru.setVar("devAdminOwner", "old-config");\n}\n'
        if number == "98":
            source += '\nscript:post-response {\n  if (bru.getVar("devAdminOwner") || bru.getVar("devAdminIdToken")) throw new Error("Cleanup failed");\n}\n'
        (suite / path.name).write_text(source, encoding="utf8")
    threading.Thread(target=server.serve_forever, daemon=True).start()
    command = [sys.argv[1], "run", "suite", "--env", "dev"]
    try:
        subprocess.run(command, cwd=root, check=True)
        # After login change only the expected UID. Even at the same backend URL
        # the real runner must reject TOTP and revoke before sending either request.
        login = next(suite.glob("00-admin*.bru"))
        with login.open("a", encoding="utf8") as stream:
            stream.write('\ntests {\n  bru.setEnvVar("firebase_admin_expected_uid", "another-admin");\n}\n')
        denied = subprocess.run(command, cwd=root, capture_output=True, encoding="utf8", errors="replace")
        assert denied.returncode != 0 and "original DEV configuration" in denied.stdout + denied.stderr
        assert seen == ["/app", "/login?key=mock", "/api/admin-auth/verify", "/api/local-dev/user-reliability/fixture", "/api/admin-auth/revoke",
                        "/app", "/login?key=mock"], seen
        print("Same-URL identity switch blocked before transmission.")
    finally:
        server.shutdown()
