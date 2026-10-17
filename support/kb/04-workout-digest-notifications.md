# Workout digest and background work

The workout digest is a periodic background job (Android WorkManager). It reads the workouts that were logged in the app and prepares a summary that the "get workout summary" tool returns.

## How it works

- The job is scheduled when the app starts and repeats about every 15 minutes, which is the minimum interval Android allows for periodic work.
- WorkManager does not guarantee an exact time. Android may delay the job in Doze mode, in battery saver mode and for apps that are restricted in the background.
- The digest only summarises logged workouts. If nothing was logged, the summary is empty: that is not an error.

## The digest does not arrive or is outdated

1. Open Android settings, then Apps, then Personal Trainer, then Battery, and choose "Unrestricted" (on some phones "Don't optimize"). Aggressive battery managers of some manufacturers also need the app to be added to the "protected" or "autostart" list.
2. Make sure the app was not force-stopped: a force-stopped app does not run background work until the user opens it again.
3. Open the app once after a restart of the phone so that the job is scheduled again.
4. Log a workout and wait at least 15 to 30 minutes; the summary is refreshed by the next run.
5. Notifications must be allowed for the app (Android 13 and newer asks for permission at first start).

If the summary is still empty after these steps and workouts are logged, collect the app version and Android version and escalate to the developers.
