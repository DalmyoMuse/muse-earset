# Muse Earset

Talk to Muse through a Bluetooth earset: press your earset's assistant gesture, speak,
and hear the answer in your ear - in a voice you picked.

**Status: early prototype (0.3).**

## How it fits together

This app is the ears and voice on top of a phone that is already a Muse Gadget (the
bridge app + Termux setup from `phone-as-muse-gadget`):

- You speak -> the app listens (through your chosen earset's microphone, via Bluetooth
  SCO) -> the text goes to `termux/earset_bridge.py` on the same phone -> the bridge hands
  it to your paired Muse through the stock gadget SDK socket, in a dedicated chat.
- Muse answers by running the `earset-say` gadget command -> the text comes back to this
  app -> the app speaks it with your chosen voice: **Google Cloud TTS** (Chirp3-HD voices,
  your own API key) or the phone's **built-in TTS**.

## Settings - exactly two

1. **Your earset**: pick it from the paired Bluetooth devices. Its microphone is used for
   talk sessions; leave unset to use the phone mic.
2. **The answering voice**: Google Cloud (key + Chirp3-HD voice) or built-in (engine voice).

Entry is the system assistant intent (`VOICE_COMMAND`): set Muse Earset as the device
assistant, or map your earset's gesture to it, and a talk session starts. Press the gesture
again to send early; otherwise it sends after a short pause.

## Termux side

Copy `termux/` next to your gadget setup, make `earset-say` executable and put it on the
gadget's command PATH, and run `earset_bridge.py` alongside the gadget service.

## Notes

- Speech recognition is the phone's system recognizer (Google), in your device language.
- The cloud voice needs a Google Cloud Text-to-Speech API key; it is stored only in the
  app's private storage and never leaves the phone except to Google's API.
- Listening happens only inside a session you started; nothing is recorded or sent
  otherwise. Recognized text itself is never written to the app's log (lengths only).
