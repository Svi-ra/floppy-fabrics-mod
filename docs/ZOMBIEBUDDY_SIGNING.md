# ZombieBuddy signing

ZombieBuddy asks players to approve every Java mod jar it loads. A **signed** jar shows up as signed by a known Steam
author, and a player can choose to trust that author once; later versions signed with the same key then load without
another prompt. Unsigned jars are re-prompted every time the jar changes (its hash changes on every update).
Upstream docs: [ModSigning.md](https://github.com/zed-0xff/ZombieBuddy/blob/master/doc/ModSigning.md),
[zb-gradle-plugin](https://github.com/zed-0xff/zb-gradle-plugin). Decision: D105.

## How it works

- `FloppyFabrics.jar.zbs` sits next to `FloppyFabrics.jar` (`42/media/java/client/`), same basename:

  ```
  ZBS
  SteamID64:76561199001294613
  Signature:<128 hex>
  ```

- The signature is Ed25519 over `ZBS:<SteamID64>:<lowercase sha256 hex of the jar>`. Any rebuild changes the jar, so the
  `.zbs` is regenerated whenever the jar changes; a `.zbs` from another build does not validate.
- ZombieBuddy finds the author's public key in its `authors.json` registry first, then in the author's **Steam profile
  summary** as `JavaModZBS:<64 hex>`. For Workshop installs the signer must also be the Workshop uploader (svi_ra,
  76561199001294613, uploads item 3842366760).

## This repo

- `pz-adapter/build.gradle` applies `io.github.zed-0xff.zb-gradle-plugin` 1.0.3. `assembleMod` depends on
  `signJarZBS` and copies the `.zbs` next to the jar, so `deployMod` / `deployWorkshop` always ship a matching pair.
- Signing is configured per machine in `gradle.properties` (git-ignored):

  ```
  zbsSteamID64=76561199001294613
  zbsPrivateKeyFile=C:/Users/user/.signing/ed25519-private.der
  ```

  Without these two lines the build still works but ships the jar **unsigned** and `assembleMod` prints a warning.
- Public key (safe to publish):
  `JavaModZBS:07747e406ab75140361389f88c0242f1ab458d897dcd686b56319d1e6d954c63`
  (`.\gradlew.bat :pz-adapter:signJarZBS --rerun` prints it again).

## The private key

`C:\Users\user\.signing\ed25519-private.der` (Ed25519, PKCS#8 DER, made with the JDK's `KeyPairGenerator` because OpenSSL
is not installed; same format as `openssl genpkey -algorithm ed25519 -outform DER`).

- **Never commit it or put it in the mod folder.** Anyone with it can sign jars as you.
- **Back it up** somewhere offline (password manager, USB stick). If it is lost, a new key means players who trusted the
  old key get prompted again, and the Steam profile / registry entry must be replaced.
- On another machine, copy the key file there and set the two properties above.

## One-time steps for the author (manual)

1. **Steam profile** (required unless the registry entry below is merged): Steam → your profile → *Edit Profile* →
   *Summary*, add this line anywhere and save. The profile must be public for ZombieBuddy to read it.

   ```
   JavaModZBS:07747e406ab75140361389f88c0242f1ab458d897dcd686b56319d1e6d954c63
   ```

2. **Authors registry** (recommended; works even with a private profile and avoids Steam rate limits): open a PR on
   [zed-0xff/ZombieBuddy](https://github.com/zed-0xff/ZombieBuddy) adding **only** `authors/svi_ra.json`
   (do not edit `authors.json`; it is rebuilt and re-signed from `authors/*.json`):

   ```json
   {
     "id": 76561199001294613,
     "name": "svi_ra",
     "keys": [
       "07747e406ab75140361389f88c0242f1ab458d897dcd686b56319d1e6d954c63"
     ]
   }
   ```

3. Upload the next Workshop version (it already contains the `.zbs`).

Status (2026-11-20): step 1 done; ZombieBuddy 2.3.4's `ZBSVerifier` returns ValidSignature for the signed jar with the
key from the Steam profile. Step 2 opened as
[zed-0xff/ZombieBuddy#148](https://github.com/zed-0xff/ZombieBuddy/pull/148) (from the fork Svi-ra/ZombieBuddy, branch
`add-author-svi_ra`), waiting for review.

## Known issue: "invalid ZBS" for some players (found 2026-11-20, v0.7.0)

Not a stale upload: the live item (599,746 bytes, updated 09:24) was downloaded with SteamCMD and is byte-identical to
`deployWorkshop`'s folder. ZombieBuddy 2.3.4's own `ZBSVerifier.check` on that download returns ValidSignature with
uploader = signer.

The cause is the key lookup. With svi_ra not in the authors registry yet, every player's ZombieBuddy fetches
`https://steamcommunity.com/profiles/76561199001294613` to read `JavaModZBS:`. If that request fails (steamcommunity
blocked or rate-limited, proxy or TLS trouble, timeout), ZombieBuddy does **not** fall back to "unsigned". It returns
"Could not verify signature", marks the mod `invalid ZBS` and blocks it. Reproduced by running the verifier with an
empty trust store.

Fix: get #148 merged. ZombieBuddy downloads the registry from
`raw.githubusercontent.com/zed-0xff/ZombieBuddy/refs/heads/master/authors.json` and caches it, so no ZombieBuddy release
is needed. The key then resolves without steamcommunity. To confirm a player report, ask for their `console.txt` and
look for `ZBS verification invalid ... detailedMessage=`: a network or HTTP error there means this issue. Shipping
without a `.zbs` is no stopgap, because ZombieBuddy blocks unsigned jars when `allow_unsigned_mods=false`.

## Checking a build

`ZBSVerifier` in ZombieBuddy 2.3.4 parses our `.zbs` and, before step 1 is done, reports only *"Could not find
JavaModZBS:<64 hex> on Steam profile"*: everything else is in place. After step 1 (or 2), the in-game approval dialog
should name svi_ra as the signer and offer to trust the author.
