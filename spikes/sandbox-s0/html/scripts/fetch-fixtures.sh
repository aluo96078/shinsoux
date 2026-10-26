#!/bin/sh
# Re-downloads the public pages used as golden fixtures (anonymous GET, no cookies, no login).
# The pages change over time, so a re-fetch produces different content: delete
# build/html-golden/expected.txt (scripts/run-golden.sh regenerates the jsoup expectation) afterwards.
# golden/fixtures/edge-cases.html is hand-written and is not fetched.
set -e
DIR=$(cd "$(dirname "$0")/../golden/fixtures" && pwd)
get() { curl -s -m 30 -L -A "Mozilla/5.0" -o "$DIR/$2" "$1"; echo "$2 $(wc -c < "$DIR/$2") bytes"; }
get https://e-hentai.org/ ehentai-front.html
get https://tw.manhuagui.com/ manhuagui-home.html
get https://www.wnacg.com/ wnacg-home.html
get https://www.mangacopy.com/ mangacopy-home.html
get https://bilimanga.net/ bilimanga-home.html
get https://www.dm5.com/ dm5-home.html
get https://e-hentai.org/g/4281212/5ebf7b8db9/ ehentai-gallery.html
get https://tw.manhuagui.com/comic/32602/ manhuagui-comic.html
get https://www.wnacg.com/photos-index-aid-390033.html wnacg-album.html
get https://www.mangacopy.com/comic/buyaoxiaoqiaomianbaoa mangacopy-comic.html
get https://www.dm5.com/manhua-new/ dm5-comic.html
get https://www.bilimanga.net/detail/917.html bilimanga-detail.html
