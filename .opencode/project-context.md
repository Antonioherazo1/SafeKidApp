# SafeKidApp - Project Context

## Repos
- **Android App**: `SafeKidApp` (GitHub: Antonioherazo1/SafeKidApp)
- **Backend**: `safekid-backend` (separate repo on EC2, GitHub: Antonioherazo1/safekid-backend, remote at ~/safekid-backend)
- Backend dir `backend/` is gitignored in SafeKidApp

## Architecture
- Android app single APK, two login modes (Parent/Child)
- Backend: FastAPI + PostgreSQL async (asyncpg) + Docker on EC2
- Server URL: `https://thinc.site/api/safekid` (hardcoded on first launch)
- API proxied by Nginx with `^~ /api/safekid/` prefix
- JWT auth (jose), bcrypt passwords, API keys for device auth

## Database Tables (prefix `safekid_`)
- `safekid_users` - id (UUID), username, password_hash, role, parent_code, parent_id, created_at
- `safekid_devices` - id (UUID), name, api_key, daily_limit_minutes, is_active, created_at, updated_at
- `safekid_user_devices` - id, user_id, device_id (unique constraint)
- `safekid_commands` - id, from_user_id, to_device_id, command_type, payload, status, created_at
- `safekid_daily_usage` - id, device_id, date, total_seconds, created_at, updated_at
- `safekid_usage_sessions` - id, device_id, date, start_time, end_time, duration_seconds, created_at

## Parent-Child Linking
- `parent_code` (6-char alphanumeric) generated on parent registration
- Child enters parent_code at signup -> `parent_id` set on child User
- Auto-creates Device + UserDevice for child during registration with parent_code

## API Endpoints (all under /api/safekid)
### Auth (`backend/app/routers/auth.py`)
- `POST /auth/register` - register (role: parent/child), returns token, user_id, username, role, parent_code, device_id, api_key
- `POST /auth/login` - login, same response fields
- `GET /auth/me` - current user info
- `POST /auth/change-password` - change password
- `POST /auth/delete-account` - delete user (also deletes children if parent)

### Parent (`backend/app/routers/parent.py`)
- `GET /parent/code` - returns parent_code
- `GET /parent/children` - list children with device info
- `POST /parent/register-child-device` - register device for child
- `POST /parent/set-limit?child_username=X&limit_minutes=Y` - set daily limit

### Commands (`backend/app/routers/commands.py`)
- `POST /commands/send` - send command (block/unblock/start_tracking/stop_tracking)
- `GET /commands/pending` - child polls pending commands
- `POST /commands/:id/delivered` - mark delivered
- `POST /commands/:id/executed` - mark executed

### Device (`backend/app/routers/devices.py`)
- `POST /device/register` - register device (JWT auth)

### Usage (`backend/app/routers/usage.py`)
- `POST /sync` - sync usage data (api_key auth)
- `GET /stats?days=N` - fetch stats

## Android App Files
- `LoginActivity.kt` - launcher, username/password login
- `SignupActivity.kt` - registration with role selector, parent_code for child
- `ParentDashboardActivity.kt` - parent code display, children list, settings
- `ChildDashboardActivity.kt` - child usage view, command polling every 15s
- `ChildDetailActivity.kt` - parent manages one child (limit, commands)
- `SettingsActivity.kt` - password, admin, limit, tracking, kiosk, cloud, delete account
- `SyncClient.kt` - HTTP client with all API methods
- `TokenManager.kt` - SharedPreferences wrapper for JWT/user data
- `UsageTracker.kt` - local usage tracking
- `UsageService.kt` - foreground service for tracking
- `MainActivity.kt` - original kiosk/blocker activity
- `HomeActivity.kt` - original home screen

## Known Issues (Fixed)
- "No value for username" when listing children -> Fixed by adding username to ChildInfo schema
- "Child not linked to your account" -> Fixed by parent_code linking during registration
- "Child has no registered device" -> Fixed by auto-creating device during child registration
- Error 500 on register -> Fixed by adding parent_code column migration
- Crash on ParentDashboardActivity -> Fixed by wrapping JSON parsing in try-catch

## Deployment
### EC2 Backend Update (manual, different repo):
```bash
cd ~/safekid-backend
curl -o app/schemas.py https://raw.githubusercontent.com/Antonioherazo1/SafeKidApp/main/backend/app/schemas.py
curl -o app/routers/auth.py https://raw.githubusercontent.com/Antonioherazo1/SafeKidApp/main/backend/app/routers/auth.py
curl -o app/routers/parent.py https://raw.githubusercontent.com/Antonioherazo1/SafeKidApp/main/backend/app/routers/parent.py
docker compose up -d --build
```

### Android APK:
- Build > Clean Project > Rebuild Project > Install on device

### Database Migration (on EC2):
```bash
docker run --rm --network=host -i postgres:16-alpine psql postgresql://postgres:postgres@localhost:5433/safekid <<'SQL'
ALTER TABLE safekid_users ADD COLUMN IF NOT EXISTS parent_code VARCHAR(10) UNIQUE;
CREATE INDEX IF NOT EXISTS ix_safekid_users_parent_code ON safekid_users (parent_code);
ALTER TABLE safekid_users ADD COLUMN IF NOT EXISTS parent_id UUID REFERENCES safekid_users(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS ix_safekid_users_parent_id ON safekid_users (parent_id);
UPDATE safekid_users SET parent_code = upper(substr(md5(random()::text || clock_timestamp()::text), 1, 6))
WHERE role = 'parent' AND parent_code IS NULL;
SQL
```

## Next Steps
1. User needs to rebuild APK and pull backend changes
2. If new sessions start, first read this file to get context
