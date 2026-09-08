# Google Auth Manual Smoke Flow

This folder does not implement Google OAuth or call Firebase password sign-in.

To run it:

1. Obtain a real Google OAuth ID token for the Firebase project externally.
2. Set `google_oauth_id_token` and `firebase_api_key` in the active Bruno
   environment.
3. Run `00 Exchange Google ID Token For Firebase Token`; it calls Firebase
   Identity Toolkit `accounts:signInWithIdp` and stores
   `firebase_google_id_token`.
4. Run the Reals provision/get-me requests.

Alternatively, set `firebase_google_id_token` directly if you already have a
Firebase ID token whose `firebase.sign_in_provider` is `google.com`. Set
`firebase_google_email` if you want the response email checked too.
