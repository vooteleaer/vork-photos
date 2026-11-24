#!/bin/sh
# Makes the vØrk Photos package for Haiku (an hpkg) from the app folder
# build.sh made: the app with its library in apps/vork_photos, and an
# entry in the Deskbar's Applications menu. vØrk storage itself (the daemon) comes
# from the vork-storage package.
#
#     haiku/build.sh && haiku/package.sh
#     pkgman install haiku/build/vork_photos-<version>-x86_64.hpkg
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
APP=$HERE/build/Photos
STAGE=$HERE/build/package
# The revision: the commit count (vork docs/storage/versioning.md), given as REVISION
# where the sources are copied without .git.
REVISION=${REVISION:-$(git -C "$ROOT" rev-list --count HEAD 2>/dev/null)}
VERSION=$(sed -n 's/^version = "\(.*\)"/\1/p' "$ROOT/Cargo.toml" | head -1 | tr -d '\r')-${REVISION:?no REVISION (the commit count)}
ARCH=x86_64

for f in Photos lib/libvork_photos.so; do
	[ -f "$APP/$f" ] || { echo "missing $APP/$f: build it first" >&2; exit 1; }
done

rm -rf "${STAGE:?}"
mkdir -p "$STAGE/apps" "$STAGE/data/deskbar/menu/Applications"
cp -a "$APP" "$STAGE/apps/vork_photos"
ln -s ../../../../apps/vork_photos/Photos \
	"$STAGE/data/deskbar/menu/Applications/vØrk Photos"

cat > "$STAGE/.PackageInfo" <<EOF
name			vork_photos
version			$VERSION
architecture	$ARCH
summary			"Photos on vØrk storage"
description		"Photos on vØrk storage: every picture in your libraries by month, as the \
thumbnails the extractor keeps with each; favourites, albums and people (your \
contacts), and a viewer to tag people and file photos in albums. A working \
prototype."
packager		"vØrk <https://github.com/vooteleaer/vork>"
vendor			"vØrk"
licenses {
	"MIT"
}
copyrights {
	"2026 vØrk contributors"
}
provides {
	vork_photos = $VERSION
	app:vork_photos = $VERSION
}
requires {
	haiku
	vork_storage
	lib:libgcc_s
	lib:libstdc++
}
urls {
	"https://github.com/vooteleaer/vork"
}
EOF

PKG=$HERE/build/vork_photos-$VERSION-$ARCH.hpkg
rm -f "$PKG"
package create -C "$STAGE" "$PKG"
echo "== $PKG"
package list "$PKG" | tail -12
