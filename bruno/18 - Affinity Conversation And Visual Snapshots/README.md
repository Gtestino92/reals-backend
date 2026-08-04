# PR3 Affinity Conversation And Visual Snapshots

Run these requests promptly and in order. The local first chat currently has a five-minute inactivity timeout and a fifteen-minute absolute timeout.

## Scenario 1: User A and User B shared affinity

1. `10 Restore User A Reference Answers`
2. `11 Restore User B Reference Answers`
3. `12 Dequeue User A`
4. `13 Dequeue User B`
5. `14 Dequeue User C`
6. `15 Dequeue Control User`
7. `16 Enqueue User A`
8. `17 Enqueue User B`
9. `18 Process A-B Match`
10. `20 Get Initial Chat As A`
11. `21 Get Initial Chat As B`
12. `22 Mutate User A Music Answers`
13. `23 Get Same Chat After Mutation As A`
14. `24 Get Same Chat After Mutation As B`
15. `25 Send Participation Message As A`
16. `26 Send Participation Message As B`
17. `27 Request Next Question As A`
18. `28 Request Next Question As B`
19. `29 Get Advanced Chat As A`
20. `30 Get Advanced Chat As B`
21. `31 Approve First Chat As A`
22. `32 Approve First Chat As B`
23. `33 Get Visual Profile As A`
24. `34 Get Visual Profile As B`
25. `35 Restore User A Reference Answers`

This proves symmetric prompt snapshots, same-chat immutability after answer mutation, persisted second-question advancement, positive-only visual indicators, indicator immutability, and absence of answer/scoring leakage.

## Scenario 2: Control User and User B without shared evidence

1. `40 Confirm Control User Has Zero Answers`
2. `41 Dequeue Control User`
3. `42 Dequeue User B`
4. `43 Dequeue User A`
5. `44 Dequeue User C`
6. `45 Enqueue Control User`
7. `46 Enqueue User B`
8. `47 Process Control-B Match`
9. `48 Get Control Chat`
10. `49 Get B Chat`
11. `50 Approve Control First Chat`
12. `51 Approve B First Chat`
13. `52 Get Control Visual Profile`
14. `53 Get B Visual Profile`
15. `54 Cleanup Control Queue`
16. `55 Cleanup User B Queue`

This proves no-answer neutral fallback, generic guidance, no matchmaking blockage, and empty visual indicators.

The flow intentionally leaves created match history in place and restores User A's reference answers.
