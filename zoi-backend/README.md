# Zoi Core

FastAPI backend for the Zoi Android companion. It keeps the OpenRouter key on the server and exposes a small chat API.

## Deploy
Use Render -> New + -> Blueprint and select this repository. Render reads the root render.yaml. Set OPENROUTER_API_KEY in the service Environment settings. OPENROUTER_MODEL defaults to openrouter/auto; change it to any model slug available to your OpenRouter account.

Check /health after deployment. It should return status "ok" and configured true.

Never commit a real provider key or place it in the Android app.
