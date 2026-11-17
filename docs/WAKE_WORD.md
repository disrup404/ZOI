# Zoi wake-word integration

Zoi uses sherpa-onnx 2.0.1 for local keyword spotting with the English GigaSpeech Zipformer KWS model.

## Build preparation

Run `bash ./prepare-sherpa.sh` from this directory before building. The script downloads the official Android AAR and the official English KWS model archive, then stages the runtime library and the small set of model files required by the app. The build workflow runs this automatically.

The downloaded AAR and model files are generated build inputs and are intentionally not committed to Git.

## Runtime behaviour

- The user explicitly enables wake-word listening from the visible Zoi app.
- Android displays an ongoing microphone foreground-service notification with a stop action.
- Audio used for the wake phrase is processed on-device. It is not uploaded to Zoi Core.
- After "Hello Zoi" is detected, Android speech recognition captures the following request. The transcript is sent to the existing Zoi Core endpoint and its response is spoken with the phone's installed TTS voice.
- The user can stop wake-word listening from the persistent notification.

## Android limitations

The microphone foreground service must be started while Zoi is visible and microphone permission is granted. Android and device manufacturers may still stop background services because of battery management, force-stop, reboot, or permission changes. This is not a guaranteed system-level hotword and does not promise operation after the app is force-stopped or the phone is rebooted.

The GigaSpeech keyword model supports English. Detection quality for "Hello Zoi" must be tested in real environments and may need threshold tuning. This first implementation does not yet support a user-configurable wake phrase or custom voice cloning.
