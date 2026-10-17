# Sdot

Talk to your running [pi](https://pi.dev) coding-agent session from your Android phone, hands-free, like a phone call.

Sdot is **not another agent**. It gives your existing pi session ears and a mouth: what you say is injected into the session as if you had typed it, and what the agent writes is read aloud to you. Same model, same context, same history. When you are back at the keyboard, the whole conversation is in the session.

```
Android phone (Sdot app)                    Your computer (pi session + Sdot extension)
────────────────────────                    ───────────────────────────────────────────
mic  ─ on-device speech-to-text ─ text ───► injected into the session (prompt or steer)
speaker ◄─ text-to-speech ◄─ sentences ◄──── streamed while the agent writes
                 ▲                                   ▲
                 └──── HTTPS over your private network (e.g. Tailscale) ────┘
```

- **`extension/`** — pi extension. Adds the `/call` command and a small authenticated API.
- **`android/`** — native Android app (Android 13+). Speech recognition runs on the device, so no voice audio leaves your phone. Free, no accounts, no third-party libraries.

## How a call feels

- Say what you want. If the agent is idle it starts working; if it is busy, your words steer the current run.
- Speak naturally: pauses of up to about 2 seconds stay in the same message. A longer silence sends what you said.
- The agent answers in short spoken sentences while it writes them. Code, diffs and paths stay in the terminal.
- While it works you hear a soft tick for each tool it runs, and a tone when it finishes.
- The call screen looks like a phone call: a circle with the name of your pi session, the call time, **Mute** and **Hang up**. The circle glows while the agent talks.
- **With a headset** you can interrupt it mid-sentence just by talking. On the phone speaker it pauses listening while it talks (otherwise it would hear itself); tap the circle to interrupt.
- **Mute** stops the microphone: nothing you say is sent until you unmute. The call notification has Mute and Hang up too.
- The **⋯** menu has the transcript, **Stop agent** (aborts the current run, like pressing Escape), **Check for updates** and **Unpair**.

Expect a short pause (a few seconds) before each answer: the reply comes from your coding model, not from a dedicated speech-to-speech model.

## Requirements

- pi with this extension.
- An Android 13+ phone.
- A private network that gives your computer an HTTPS address reachable from the phone. [Tailscale](https://tailscale.com) does this for free and is what this guide uses.

## Setup

### 1. Install the extension

```sh
git clone <this repository> sdot
pi install ./sdot/extension
```

### 2. Expose it to your private network (once)

The extension only listens on `127.0.0.1`. Publish it inside your tailnet over HTTPS:

```sh
tailscale serve --bg --https=8443 http://127.0.0.1:8787
```

This needs [HTTPS certificates](https://tailscale.com/kb/1153/enabling-https) enabled for your tailnet. **Never use `tailscale funnel`** for this: it would publish your session to the internet.

To undo it: `tailscale serve --https=8443 off`.

### 3. Download the offline speech pack on the phone

Sdot only uses on-device recognition. Download your language once:

- **Settings → System → Languages → On-device speech recognition**, or
- **Google app → Settings → Voice → Offline speech recognition**.

The app also offers to start the download when your language is missing.

For a natural voice, use **Speech Services by Google** as the text-to-speech engine with the offline voice for your language installed: **Settings → Text-to-speech output → Preferred engine → Speech Services by Google**, then **Install voice data** and download your language. Sdot picks the best installed offline voice and shows its name when a call starts.

### 4. Install the app

Install the APK from the project's releases page, or build it yourself (see [Building the app](#building-the-app)) and install `app-debug.apk`. See [Updates](#updates) before mixing the two.

### 5. Pair and call

In the pi session you want to talk to:

```
/call
```

It prints a pairing code (`sdot1:…`). Copy it, paste it in the app, tap **Pair**, then **Call**. You only pair once; afterwards just run `/call` and tap **Call**.

## `/call` command

| Command | What it does |
| --- | --- |
| `/call` or `/call start [port]` | Starts the call server for this session (default port 8787) and prints the pairing code. |
| `/call pair <https-url>` | Prints a pairing code for an explicit HTTPS URL, when it cannot be detected. |
| `/call status` | Shows whether the server runs and how many phones are connected. |
| `/call stop` | Stops the server. It also stops when the session ends. |

One session at a time owns a port, so the call always talks to the session where you ran `/call`.

While a phone is connected, the extension adds a short "voice call" instruction to the system prompt, so the agent answers in speakable sentences. It is removed when you hang up.

### Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `SDOT_PORT` | `8787` | Local port for `/call start`. |
| `SDOT_HTTPS_PORT` | `8443` | HTTPS port used to build the pairing URL from your Tailscale name. |
| `SDOT_PUBLIC_URL` | detected | Full HTTPS URL to put in the pairing code (for setups other than Tailscale). |
| `SDOT_CONFIG_DIR` | `$XDG_CONFIG_HOME/sdot` or `~/.config/sdot` | Where the access token is stored. |

## Security

- The extension binds to `127.0.0.1` only. Nothing listens on your network interfaces.
- Every request needs a bearer token: 32 random bytes, generated on first use, stored with mode `0600` in the config directory and compared in constant time.
- The pairing code contains that token. **Treat it like a password**: anyone with it and network access can give instructions to your agent, which can run commands on your computer.
- Transport is HTTPS (Tailscale serve certificates) inside your private network. The app refuses plain HTTP and does not follow redirects.
- The app stores the pairing in private storage, excluded from cloud backups, and never shows or logs the token.
- To revoke access, delete the `token` file in the config directory and pair again.
- The update check talks only to `api.github.com` without credentials. APK downloads follow redirects only to GitHub's HTTPS download hosts, are size-limited, and are installed only when the package name matches, the version is higher and the signing certificate is identical to the installed app.

## Building the app

You need JDK 17+ and the Android SDK (platform 36).

```sh
cd android
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The APK is in `android/app/build/outputs/apk/debug/app-debug.apk`.

## Updates

Published releases include **Check for updates** in the **⋯** menu (it is hidden in builds made without `-Psdot.updateRepo=<owner>/<repo>`). It asks GitHub for the latest release of the configured repository and, when it is newer than the installed version, offers **Download & install**: the APK is downloaded over HTTPS from GitHub into the app's private storage, checked, and handed to Android, which asks you to confirm. The first time, Android asks you to allow **Install unknown apps** for Sdot.

Sdot only installs an update that is the same app, has a higher version, and is signed with the **same key** as the installed app. A debug build you compiled yourself is signed with a different key, so to switch from it to release builds, uninstall Sdot once and install the release APK from the releases page (you will need to pair again). After that, updates work from the menu.

## Releasing

For maintainers publishing APKs on GitHub Releases.

1. Create a release keystore once, outside the repository:

   ```sh
   keytool -genkeypair -v -keystore /safe/place/sdot-release.jks -alias sdot \
     -keyalg RSA -keysize 4096 -validity 10000
   ```

   Never commit the keystore or its passwords, and back both up: if you lose them, installed apps can no longer be updated and users must uninstall and reinstall.

2. Build, test and sign:

   ```sh
   export SDOT_UPDATE_REPO=<owner>/<repo>
   export SDOT_VERSION=1.2.3
   export SDOT_KEYSTORE=/safe/place/sdot-release.jks
   export SDOT_KEY_ALIAS=sdot
   export SDOT_KEYSTORE_PASSWORD=... SDOT_KEY_PASSWORD=...
   scripts/release-android.sh
   ```

   The version code is derived from the version (`major * 10000 + minor * 100 + patch`, minor and patch up to 99), so every release must use a higher version. The script prints the APK path, its SHA-256 and a suggested `gh release create v<version> ...` command.

3. Review the APK and publish it with that `gh release create` command. The repository must be public for the app to see its releases.

## Development

```sh
cd extension
pnpm install
pnpm test        # node:test, no build step
pnpm typecheck
```

Extension API (all routes need `Authorization: Bearer <token>`):

| Route | Purpose |
| --- | --- |
| `GET /v1/events` | Server-Sent Events: `hello`, `speech`, `activity`, `busy`, `idle`. |
| `POST /v1/say` | `{"text": "..."}` — inject what the user said. |
| `POST /v1/abort` | Abort the current run. |
| `GET /v1/status` | Session state. |

## Known limitations

- Updates come from GitHub Releases only; there is no store release.
- Some speech engines play a short beep each time listening restarts.
- On the phone speaker, anything you say while the agent is talking is ignored by design (use a headset, or tap the circle to interrupt).

## License

[MIT](LICENSE)
