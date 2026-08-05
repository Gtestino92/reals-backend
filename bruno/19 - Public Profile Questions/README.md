# Public Profile Questions

Focused manual smoke flow for optional public profile questions.

Prerequisites:

- `profile_questions_user_a_id_token` authenticates the participant whose profile-question answers are configured by this folder.
- `profile_questions_counterpart_id_token` authenticates the counterpart who can call the guarded visual-profile endpoint for User A.
- `profile_questions_visual_match_id` points to an existing match where the counterpart can currently access User A through `GET /api/matches/{matchId}/visual-profile`.
- User A has current legal requirements satisfied before write requests are run.

Run requests in order. The visual request may fail with the normal visual-profile access error if the configured match is not in a visual-accessible state.

Assertions cover catalog fields, four private answers, three selected answers, reorder/replace behavior, visual shape, absence of the fourth unselected answer, and selected-position compaction after delete.
