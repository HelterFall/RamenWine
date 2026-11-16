# Wawona Weston SHM thread lifecycle finding

## Scope

RamenWine currently packages the official Wawona Android v26.8.12 binary release, pinned to source revision `75ffe2982bd7d99b29b7e743c4f70a9becf61f86`. The APK is downloaded and checksum-verified by `tools/fetch_wawona_android.sh`; this workflow does not compile Wawona from source.

## Confirmed upstream behavior

In the pinned upstream source file `src/platform/android/android_jni.c`:

- `nativeRunWestonSimpleSHM` increments `g_weston_shm_count`, creates a pthread, and detaches it with `pthread_detach` (around lines 4532–4549).
- The worker decrements `g_weston_shm_count` only when `weston_simple_shm_thread_func` returns.
- `nativeStopWestonSimpleSHM` clears `g_simple_shm_running` and then immediately writes zero to `g_weston_shm_count` (around lines 4552–4569).
- `nativeIsWestonSimpleSHMRunning` reports only whether that counter is greater than zero (around lines 4571–4577).

The stop API therefore reports “not running” immediately after a stop request; it does not prove that the detached pthread has exited. There is no join or completion-wait API in this pinned JNI surface.

## Why the current Kotlin adapter cannot prove shutdown

RamenWine calls the stop method during Surface detach and display-session shutdown. It then tears down the native surface/compositor. Polling `nativeIsWestonSimpleSHMRunning` is not a valid join substitute because the upstream stop method resets the counter before the worker returns. A fixed sleep is only a timing guess and must not be treated as a correctness guarantee.

This matches the saved 2026-11-13 device diagnostic: a later display initialization was rejected because the previous Weston SHM test thread had not exited. The failure occurs in the display lifecycle before Wine prefix setup or Windows EXE execution.

## Correct fix

The durable fix belongs in the native Wawona build, not in another Kotlin timeout:

1. Keep the SHM worker thread joinable and retain its `pthread_t`.
2. Serialize start/stop operations with a native mutex.
3. On stop, request termination and `pthread_join` the worker before returning.
4. Keep the running state true until the worker has actually returned; do not clear the count pre-emptively.
5. Make repeated start/stop calls idempotent and test the race between natural exit and explicit stop.

Then build and package a RamenWine-specific Wawona native library from the pinned source revision, with provenance and hashes updated. Until that build exists and is tested on a physical device, do not claim that repeated Wawona open/close cycles are fixed.

## Safe validation sequence

- First run the focused Android compile/unit-test job without building the large offline runtime.
- Build the custom Wawona native payload only after the native patch and its host-side tests pass.
- On a physical ARM64 device, repeat open → SHM smoke test → close → reopen several times, and confirm the native worker has joined before compositor teardown.
- Only then proceed to the Wine/GDI smoke test.

## References

- [Pinned Wawona source: android_jni.c](https://github.com/Wawona/Wawona/blob/75ffe2982bd7d99b29b7e743c4f70a9becf61f86/src/platform/android/android_jni.c)
- [RamenWine Wawona importer](../tools/fetch_wawona_android.sh)
- [Sanitized device diagnostic](SANITIZED_LAUNCH_DIAGNOSTIC.md)
