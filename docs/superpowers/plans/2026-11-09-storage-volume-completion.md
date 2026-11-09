# Storage Volume Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` to implement task-by-task with regression tests.

**Goal:** Complete the storage-volume design and correct the review findings so every file operation, lifecycle transition, and console action is volume-aware.

**Architecture:** Make `bk_storage_volume` and `bk_artifact_copy` the single source of truth. All hot/cold/import I/O uses `VolumeArtifactStore`; legacy path configuration remains migration-only. Lifecycle state changes flow through one service with database-backed ACTIVE uniqueness and task/reservation guards.

**Tech Stack:** Java 21, Spring Boot, MyBatis-Plus, MySQL, Vue 3, Element Plus, Maven, Node test runner.

**Spec:** `docs/superpowers/specs/2026-11-06-storage-volume-management-design.md`

## Global Constraints

- Never route a new file by `storage.hot-root`, `storage.managed-roots`, or `cold.import-root`.
- A file path is always `storage_volume_id + storage_key`; reject traversal and symbolic-link escapes.
- Keep one `HOT + ACTIVE` volume, and never switch it while a write job/reservation references it.
- Never infer offline media identity from a drive letter; require marker/fingerprint validation.
- Preserve offline records and report `STORAGE_VOLUME_OFFLINE` distinctly from missing files.

## Review Focus

- Active-volume transitions race with running backup jobs or reservations.
- Symlinks in a managed root escape the storage boundary.
- Old hot copies remain readable/deletable after an ACTIVE switch.
- A reattached cold volume restores directly without import copying.
- A missing eligible standby volume pauses new work and leaves running jobs pinned.

### Task 1: Repair routing, lifecycle and security foundations

**Files:** storage-volume services/router/local store, reservation service, lifecycle/router tests.

- [ ] Add failing tests for symbolic-link escape, ACTIVE state bypass, running-job/reservation lock, and per-volume cleanup.
- [ ] Make state transitions explicit and transactional; route all reads/writes/deletes by volume; remove production single-root dependencies.
- [ ] Run the targeted Maven storage tests.

### Task 2: Complete health, selection, automatic switching and configuration migration

**Files:** volume health scheduler/selection service, config validation/default SQL, overview and tests.

- [ ] Add failing tests for priority selection, threshold/health-triggered switching and no-candidate pause/alert.
- [ ] Implement scheduled probing, alert persistence/auditing and new-task selection while retaining pinned jobs.
- [ ] Remove obsolete path configuration from runtime UI/defaults and expose per-volume metrics.

### Task 3: Complete cold, import and restore flows

**Files:** cold handlers/services/controllers, restore handlers, replica resolver and tests.

- [ ] Add failing tests for reattach/direct restore, verified COLD copy cleanup, frozen restore source and IMPORT routing.
- [ ] Replace legacy hot/import I/O with volume routing and artifact-copy lifecycle updates.
- [ ] Run cold and restore regression tests.

### Task 4: Complete APIs, DTOs and console management

**Files:** storage-volume query/controller DTOs, order DTOs, Vue components/routes/tests.

- [ ] Add failing API/frontend tests for register, probe, mount, state actions, offline order availability and role guards.
- [ ] Implement paged queries, action impact responses, detail/audit data and componentized `storage-volumes` UI.
- [ ] Run Maven API tests and Node tests/build.

### Task 5: Migration rehearsal, Windows acceptance and documentation

**Files:** migration tests, Windows acceptance test/documentation, configuration docs.

- [ ] Add A/B/C E2E test and historical migration assertions.
- [ ] Document service-account, UNC, reattach and rollback procedures.
- [ ] Run complete Maven and frontend verification.
