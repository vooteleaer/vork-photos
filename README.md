# vØrk Photos

Photos on [vØrk storage](https://github.com/vooteleaer/vork), in the spirit of Immich: every picture in your libraries, wherever they came from (a phone, a mount, Explorer, an app), encrypted and synced like everything else.

> A demo app of [vØrk storage](https://github.com/vooteleaer/vork), the storage that runs on top of the vØrk network. To try it, start with [getting started](https://github.com/vooteleaer/vork/blob/main/docs/storage/getting-started.md). ADR numbers here are vØrk storage's ([docs/storage/adr](https://github.com/vooteleaer/vork/tree/main/docs/storage/adr)).

- **Timeline:** every photo by month, newest first, as square thumbnails. The thumbnails are blocks the extractor keeps with each photo (ADR-0026), so a device shows the grid without fetching originals.
- **Favourites** (`favorite = true`, a star in every vØrk storage app), **albums** (a name on its photos: `album` is a name or a list of them, so a photo is in any number of albums, in whichever libraries; an album can be moved to another library with all its photos; file views show them under `Albums/`), **people**: your contacts (ADR-0024), with their profile photos, and the photos they're tagged in (`person:<contact id>`).
- **Viewer:** the preview, what's known of the photo (when, size, camera, exposure, place, file), favourite, people (tag a contact, or a new one: they go into your address book), albums (in or out, a new one with this photo as its cover).
- Updates as the libraries change, here or on another device. Contacts and Calendar see the same records.

Faces come later from their own daemon, as proposals the user confirms (ADR-0026).

`core/` talks to `vorkd` through libvork; `gtk/` is the Linux front end (GTK 4, libadwaita). Build on Linux (or WSL): `cargo build -p vork-photos-gtk`. For demos: `VORK_PHOTOS_VIEW=favorites|album:<name>|person:<name>`, `VORK_PHOTOS_OPEN=<n>` (open the nth photo), `VORK_PHOTOS_SNAPSHOT=<file.png>`.

## Android

`android/` is the phone app (Kotlin, Compose, the same core through JNI in `android/jni`), laid out like Immich's: the timeline by day with a handle to scroll by month, long press to select (favourite, add to album, share, delete), a full-screen viewer (swipe, pinch or double tap to zoom; the original is read once zoomed in; Info), albums, and **Backup**: the pictures taken on the phone (the camera's folders, or others chosen) go into a library you pick, each described and given its thumbnails on the phone (as the extractor would, ADR-0023/0026), so every device shows it at once. New pictures are backed up when they appear (a job the system starts), after a restart, and every few hours to catch up. If you choose, what's backed up then leaves the phone (to its trash, kept 30 days; with media management allowed once, without asking each time), and the library is where the photos are.

vorkd's sockets are in the vØrk storage app's files, so the app asks the vØrk storage app (`org.vork.storage`) for each connection: the user lets Photos in once there, to the libraries chosen, and vØrk storage hands over connections made with its own app token for it (vØrk storage ADR-0004). Libraries are joined in vØrk storage; the drawer's Announces opens it.

Build: `powershell -ExecutionPolicy Bypass -File android\build-apk.ps1 [-Abis arm64-v8a]` (NDK 27, JDK 17, vork next to this repository).

## Windows

`windows/` is the Windows front end (`vork-photos.exe`, plain Win32 controls; the grid and the viewer drawn with Direct2D, pictures decoded with WIC): the libraries on the left (each ticked or not, remembered), the timeline by month, albums (open one to see its photos and move it to another library), select with click, Ctrl and Shift (Ctrl+A all) to star photos, add them to albums or delete them (Delete), a viewer (Enter or double-click; arrows, wheel or double-click to zoom, the original read once zoomed in). Build on Windows: `cargo build --release -p vork-photos-win`. For tests: `VORK_PHOTOS_OFFSCREEN=1` draws it off screen without taking the focus.

## License

[MIT](LICENSE)
