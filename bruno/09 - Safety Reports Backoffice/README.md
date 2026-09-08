# Safety Reports Backoffice Bruno requests

Agregar esta carpeta dentro de tu colección Bruno `reals-backend-happy-path`.

Variables requeridas/recomendadas en el environment:

```bru
firebase_admin_email: gtestino1992@gmail.com
firebase_admin_password: paste-admin-password-here
firebase_admin_id_token:
firebase_admin_uid:
firebase_admin_refresh_token:
firebase_app_check_token:
safety_report_status: PENDING
safetyReportId:
reportedUserId:
reporterUserId:
safetyReportStatus:
penaltyId:
temporary_penalty_hours: 24
penalty_reason: Safety report confirmed
safety_report_dismiss_notes: Report reviewed and dismissed.
safety_report_penalty_notes: Report reviewed and confirmed.
```

Orden sugerido:

1. En AWS DEV con App Check `ENFORCED`, ejecutar primero `16 AWS Dev Tooling/00b Exchange Firebase App Check Debug Token` para guardar `firebase_app_check_token`.
2. `00 Admin Firebase Sign In`
3. `01 List Pending Safety Reports`
4. `03 Get Safety Report Detail`
5. Elegir uno:
   - `04 Dismiss Safety Report`
   - `05 Apply Temporary Penalty`
   - `06 Apply Permanent Penalty`

Notas:
- `01` guarda automáticamente `safetyReportId`, `reportedUserId` y `reporterUserId` si hay reportes pendientes.
- Si no querés usar login Firebase desde Bruno, completá manualmente `firebase_admin_id_token` y, para AWS DEV, `firebase_app_check_token`.
- El email admin esperado para tu configuración local es `gtestino1992@gmail.com`.
- El debug token de App Check es sensible y no se debe commitear. Si vence el JWT `firebase_app_check_token`, repetí el intercambio; no generes ni registres otro debug token solo por ese vencimiento.
