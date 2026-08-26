# Stalker Replay Fixtures

These fixtures model sanitized portal families for the Stalker replay harness.

To onboard a new real-world portal:
1. Capture the ordered `handshake`, auth, profile, modules, catalog, and `create_link` responses.
2. Remove real domains, tokens, credentials, MAC addresses, and account identifiers.
3. Keep the request action order intact so recipe fallback behavior stays deterministic.
4. Save the fixture as `data/src/test/resources/stalker/fixtures/<family>.json`.
5. Add the file to `StalkerPortalReplayHarnessTest` and assert the expected auth mode, portal profile, fingerprint, MAG preset, and bootstrap recipe.
6. For catch-up/archive portals, include the expected `streamKind`, `catchUpStartSeconds`, `catchUpEndSeconds`, and any archive fingerprint evidence fields.

The goal is to encode new portal families as data plus recipe rules, not ad hoc code paths.

Capture/export helper:

```powershell
.\tools\stalker-har-to-fixture.ps1 `
  -InputPath .\capture.har `
  -OutputPath .\data\src\test\resources\stalker\fixtures\new_portal.json `
  -FixtureName new_portal `
  -PortalUrl https://real.portal.example/c `
  -MacAddress 00:1A:79:AA:BB:CC `
  -AuthMode AUTO `
  -Username alice `
  -Password secret
```

The script extracts Stalker `action=` requests from a HAR capture, redacts domains/tokens/MACs/credentials deterministically, and emits a replay-fixture skeleton in the same format used by `StalkerPortalReplayHarnessTest`.

Fixtures may also include an `operations` array. The acceptance harness currently understands
`MOVIE_PREVIEW`, `SERIES_PREVIEW`, `EPG`, and `BACKGROUND_INDEX`; it uses these to assert that
on-demand setup performs no item-page calls and that later work remains page-bounded.

For authorized comparisons, run `tools/stalker-trace-compare.ps1` with a reference-client HAR
and a StreamVault HAR. Its output contains only action/type/endpoint-family timing and byte counts.
For connected playback and migration evidence, run `tools/stalker-connected-validation.ps1` once
per Live channel; the script enforces `ROTATION_270`, captures 61 frames by default, hashes them,
checks the media session, and writes only filtered and redacted player logs.
