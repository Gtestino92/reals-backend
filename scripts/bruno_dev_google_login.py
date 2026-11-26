"""Loopback-only Google sign-in for an interactive Bruno Desktop request."""
import argparse
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

from google_auth_oauthlib.flow import InstalledAppFlow


class LoginHandler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # Keep credentials, callbacks and response bodies out of logs.

    def do_GET(self):
        if self.path != "/local-cleanup":
            self.send_error(404)
            return
        self.send_response(204)
        self.end_headers()

    def do_POST(self):
        if (self.path != "/login" or self.headers.get("Host") != "127.0.0.1:8765"
                or self.headers.get("Origin") or self.headers.get("X-Reals-Bruno") != "1"):
            self.send_error(403)
            return
        try:
            flow = InstalledAppFlow.from_client_secrets_file(
                self.server.client_file, scopes=["openid", "https://www.googleapis.com/auth/userinfo.email"],
                autogenerate_code_verifier=True,
            )
            credentials = flow.run_local_server(
                host="127.0.0.1", port=0, access_type="online", prompt="select_account",
                timeout_seconds=120, authorization_prompt_message=None,
                success_message="Return to Bruno to see the Firebase sign-in result.",
            )
            if not credentials.id_token:
                raise ValueError("Missing Google ID token")
            body = json.dumps({"credential": credentials.id_token}).encode("utf-8")
        except Exception:
            self.send_error(401, "Google sign-in failed. Check the Desktop client and retry within two minutes.")
            return
        self.send_response(200)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("client_file", help="Google OAuth Desktop client JSON outside the repository")
    args = parser.parse_args()
    with HTTPServer(("127.0.0.1", 8765), LoginHandler) as server:
        server.client_file = args.client_file
        print("Ready for Bruno request 00 on loopback port 8765. Ctrl+C stops the helper.")
        server.serve_forever()
