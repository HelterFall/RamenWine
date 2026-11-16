# Sanitized launch diagnostic

This note preserves the actionable failure from a RamenWine diagnostic capture dated 2026-11-13. The raw report was removed because it contained device-specific identifiers and system details.

## Failure

The launch attempt stopped while opening the Wawona display backend. It did not reach Wine prefix initialization or Windows game execution.

```text
DisplayBackendUnavailableException:
Wawona could not initialize:
A previous Weston SHM test thread has not exited;
reopen RamenWine before starting another display session.
```

The recorded stack pointed to:

- `WawonaDisplayBackend.start()`
- `GameSessionCoordinator.openDisplay()`

## Interpretation

At the time of this capture, the app could not confirm that the previous native Weston shared-memory smoke-test thread had exited. A subsequent display start was therefore rejected. This is a display/smoke-test lifecycle failure, not evidence of a Wine or game-launch failure.

## Privacy

Raw diagnostic exports can contain hardware model and build fingerprint, kernel and memory information, app/process identifiers, private filesystem paths, mount details and logcat. Do not commit raw exports. Share a sanitized incident summary containing only the error, relevant app version and source locations needed for debugging.
