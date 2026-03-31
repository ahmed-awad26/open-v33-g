# 1. Current project inspection

## Modules
- `app`
- `core/common`
- `core/crypto`
- `core/model`
- `core/ui`
- `core/vault`
- `data/db`
- `data/repository`
- `domain/contacts`
- `domain/vaults`
- `feature/contacts`
- `feature/dashboard`
- `feature/vaults`

## Package / architecture
- Multi-module Kotlin Android app.
- UI is Compose-heavy. No XML-driven feature UI was needed for this telecom upgrade.
- Navigation is a single Compose `NavHost` in `MainActivity`.
- Dependency injection uses Hilt and EntryPoints for application-scope access from Telecom / call surfaces.
- Vault storage is SQLCipher-backed Room, one encrypted DB per vault via `VaultDatabaseFactory`.
- Contact isolation is vault-scoped; active vault is provided by `VaultSessionManager`.
- Contact repository is `ContactRepository` with `ContactRepositoryImpl`.
- Call log UI reads device call history via `CallLog.Calls` in `feature/contacts/CallLogSupport.kt`.
- Contact details already supported notes, reminders, and timeline items.
- Existing telecom-related pieces already present before this round:
  - `DefaultDialerInCallService`
  - `DialerEntryActivity`
  - `CallIntentHelper`
  - `ActiveCallControlsActivity`
  - incoming call overlay/notification stack

## Current telecom baseline before this round
- App was already a default-dialer candidate.
- App already had `InCallService` and role request flow.
- No `ConnectionService` was present, and it is not required for becoming a default dialer for PSTN call control.
- Existing active-call UI already had real end / hold / mute / speaker entry points, but lacked richer state synchronization, timer, proper DTMF keypad, route awareness, call-note persistence, and efficient contact resolution.

# 2. Feasibility matrix

| Feature | Status | Notes |
|---|---|---|
| Active call screen | Fully implementable now | Existing screen upgraded, Telecom-backed |
| Caller name / number / avatar resolution | Mostly implementable now | Name/number resolved from vault contacts first; avatar pipeline already existed in app surfaces, but this round focused on timer/state/controls |
| Live call timer | Fully implementable now | Implemented from ACTIVE state timestamps |
| Mute / unmute | Fully implementable now | Real `InCallService.setMuted()` |
| Hold / unhold | Implementable when capability exists | Implemented and capability-gated |
| Speaker toggle | Fully implementable now | Real route switch |
| Bluetooth / wired audio route awareness | Telecom/OEM dependent | Implemented using `CallAudioState` route mask |
| DTMF dial pad | Fully implementable now | Real `playDtmfTone/stopDtmfTone` |
| Add call | Default-dialer / OEM dependent | Implemented as real add-call UI handoff where Telecom permits |
| End call | Fully implementable now | Real disconnect path |
| Call notes saved locally | Fully implementable now | New Room entity + migration + UI wiring |
| Contact shortcut during call | Fully implementable now | Wired to existing contact details flow |
| Dual SIM / PhoneAccount awareness | Telecom/OEM dependent | Existing SIM picker retained; active call now surfaces PhoneAccount label/id |
| Accurate call state updates | Fully implementable now | Coordinator upgraded |
| Graceful unsupported handling | Fully implementable now | Buttons hidden/disabled by runtime capability |
| Multi-call awareness | Partially implementable | Primary/secondary count tracked; no fake conference UI |
| ConnectionService / self-managed telephony | Not needed / not appropriate | Not required for carrier PSTN dialer role |

# 3. Risk analysis

## Pre-existing risks observed
- The old active-call path could repeatedly hit repository flows during call state updates.
- Contact matching for incoming/active calls was doing a full vault contact load at call callback time.
- Existing DB factory still contains a pre-existing destructive fallback for legacy unmigrated vaults; this round added a real migration for new telecom data, but did not rewrite the entire historical migration story.
- Existing active-call controls were runtime-real in some places, but UI capability mapping was shallow.

## Risks actively reduced in this round
- Added `TelecomContactResolver` cache to avoid repeated full vault list resolution on call callbacks.
- Added dedicated telecom state model to avoid stale button state.
- Added dedicated call-note table so notes survive restarts and do not rely on transient UI state.
- Kept vault separation intact by storing call notes inside each vault DB.

# 4. Architecture plan tailored to this project

## Telecom integration layer
- Keep Android Telecom as source of truth.
- Use `DefaultDialerInCallService` as real PSTN call bridge.
- Upgrade `TelecomCallCoordinator` into the runtime state mapper for:
  - primary call selection
  - hold/mute/speaker capability mapping
  - route awareness
  - timer anchors
  - multi-call count
  - DTMF dispatch
  - add-call launch path

## Contact resolution strategy
- Prefer vault-local contacts first.
- Add `TelecomContactResolver` cache keyed by vault + normalized number.
- Avoid main-thread full-contact reloading on call screen open.

## Call notes persistence
- New `call_notes` table inside each vault DB.
- Stores:
  - call note id
  - optional contact id
  - normalized phone
  - raw phone
  - direction
  - call start/end timestamps
  - duration
  - phone account label
  - note text
  - created/updated timestamps
- Contact details screen now shows call notes as a first-class section.
- Timeline gets a `CALL_NOTE_ADDED` item when the note is linked to a vault contact.

## UI strategy
- Keep existing active-call activity rather than replacing navigation structure.
- Upgrade it with:
  - live timer
  - real DTMF dialog
  - audio route chooser
  - capability-aware controls
  - contact shortcut deep-link into existing contact details

# 5. Exact file-by-file change list

## New files
- `app/src/main/java/com/opencontacts/app/TelecomContactResolver.kt`
- `data/db/src/main/java/com/opencontacts/data/db/entity/CallNoteEntity.kt`

## Modified files
- `app/src/main/java/com/opencontacts/app/ActiveCallControlsActivity.kt`
- `app/src/main/java/com/opencontacts/app/DefaultDialerInCallService.kt`
- `app/src/main/java/com/opencontacts/app/MainActivity.kt`
- `app/src/main/java/com/opencontacts/app/TelecomCallCoordinator.kt`
- `core/model/src/main/java/com/opencontacts/core/model/Models.kt`
- `data/db/src/main/java/com/opencontacts/data/db/dao/ContactsDao.kt`
- `data/db/src/main/java/com/opencontacts/data/db/database/VaultDatabase.kt`
- `data/db/src/main/java/com/opencontacts/data/db/database/VaultDatabaseFactory.kt`
- `data/db/src/main/java/com/opencontacts/data/db/mapper/Mappers.kt`
- `data/repository/src/main/java/com/opencontacts/data/repository/ContactRepositoryImpl.kt`
- `data/repository/src/main/java/com/opencontacts/data/repository/RoundTripTransferPackageCodec.kt`
- `data/repository/src/main/java/com/opencontacts/data/repository/VaultTransferRepositoryImpl.kt`
- `domain/contacts/src/main/java/com/opencontacts/domain/contacts/ContactRepository.kt`
- `feature/contacts/src/main/java/com/opencontacts/feature/contacts/ContactDetailsRoute.kt`

# 6. Manifest changes
- No new manifest components were required in this round because the project already had:
  - `DefaultDialerInCallService`
  - dialer-entry activity intent filters
  - required dialer-related permissions
- Existing default-dialer capability was preserved.

# 7. Permission and default dialer flow
- Default dialer role request remains handled through `RoleManager.ROLE_DIALER` in the existing call helper/settings flow.
- Real in-call controls still depend on default dialer role.
- PSTN actions continue to rely on existing permissions already declared in the project:
  - `CALL_PHONE`
  - `ANSWER_PHONE_CALLS`
  - `READ_PHONE_STATE`
  - `READ_CALL_LOG`
  - `READ_CONTACTS`
- No extra risky permissions were added for this round.

# 8. Database schema and migration plan
- Vault DB version bumped from `9` to `10`.
- New migration `VAULT_DB_MIGRATION_9_10` creates `call_notes` with indexes on:
  - `contact_id`
  - `normalized_phone`
  - `created_at`
  - `call_started_at`
- Existing vault/contact data is preserved.

# 9. Complete implementation code
- Full compile-target code is included in the attached modified project ZIP.
- This round intentionally changed real source files in-place instead of shipping pseudo code.

# 10. Navigation wiring
- Active in-call contact shortcut now routes into existing contact details through `MainActivity` using `EXTRA_OPEN_CONTACT_ID`.
- `MainActivity` consumes that extra and navigates to `contact/{contactId}` without replacing the existing graph.

# 11. Verification checklist
- [ ] App still opens on contacts root
- [ ] Vault switching still works
- [ ] Existing notes/reminders/timeline still load
- [ ] Default dialer request still works
- [ ] Incoming call UI still appears
- [ ] Active call screen opens without black transition
- [ ] Timer starts only when call becomes ACTIVE
- [ ] Hold button hides/disables when unsupported
- [ ] Mute toggles real mic mute
- [ ] Speaker reflects real route state
- [ ] DTMF dialog sends real tones during active call
- [ ] Add call opens real add-call path where supported
- [ ] Saving call note persists across restart
- [ ] Contact details show call-note section
- [ ] Export/import paths still compile with new `call_notes` support

# 12. Manual test scenarios
1. Make app default dialer, place outgoing call, verify:
   - timer
   - mute
   - hold if supported
   - speaker
   - DTMF
   - end call
2. During active call save a call note, reopen app, open same contact, verify note is present.
3. Receive incoming call from a vault-local contact, verify vault contact identity resolves before system fallback.
4. Use SIM-aware call entry and verify active call displays the PhoneAccount label if available.
5. Put one call on hold and place/add another, verify primary state updates and secondary count appears.

# 13. Edge-case test scenarios
- Unknown number with call note saved during call
- No Bluetooth available
- Wired headset connected after call already active
- Hold unsupported by carrier / device
- Multiple vaults with same number in different vaults; active vault must win
- Call disconnect immediately after opening the in-call screen
- Process death after saving call note, then app relaunch

# 14. Android / OEM / carrier limitations
- Some OEMs/carriers do not expose hold/add-call/audio route capabilities uniformly.
- Conference management is not faked; only surfaced where runtime state supports it.
- Bluetooth and wired route switching depends on the platform-reported route mask.
- Real Telecom control still requires default dialer role; when the app is not default, Android can restrict direct PSTN control.
