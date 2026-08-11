# Google Auth Manual Smoke Flow

This folder does not implement Google OAuth or call Firebase password sign-in.
Obtain a valid Firebase ID token from a Firebase user authenticated with
Google, then set `firebase_google_id_token` in the active Bruno environment.
Set `firebase_google_email` if you want the response email checked too.
