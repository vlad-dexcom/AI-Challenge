# Authentication and API keys

Personal Trainer has no user accounts and no sign-in screen. "Authorization" means one of two keys: the Gemini API key of the cloud mode, or the device key of the private local AI gateway (see the gateway article).

## Gemini API key (Cloud mode)

The Gemini key is built into the app when it is compiled: it is read from `local.properties` (`GEMINI_API_KEY=...`) or passed as `-PGEMINI_API_KEY=...`. There is no field in the app where a user can type it.

Typical errors and what they mean:
- `API key not valid` (HTTP 400) or `API_KEY_INVALID`: the key was mistyped, revoked or belongs to another project. Create a new key in Google AI Studio, put it into `local.properties` and rebuild the app.
- HTTP 403 `PERMISSION_DENIED`: the Generative Language API is not enabled for the key's project, or the key has restrictions (for example an Android package restriction that does not match).
- HTTP 429 `RESOURCE_EXHAUSTED`: the free quota of the key is used up. Wait for the quota to reset or use a key with billing enabled.
- A chat reply "GEMINI_API_KEY is not set": the app was built without a key. Rebuild with the key.

## Checklist

1. Open the key in Google AI Studio and make sure it is active.
2. Check that `local.properties` contains exactly one `GEMINI_API_KEY=` line without quotes or spaces.
3. Rebuild and reinstall the app: a changed key is only picked up by a new build.
