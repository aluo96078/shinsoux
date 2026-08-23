# Unified Shinsou / ShuYue repository contract

Shinsou X accepts the historical array forms and the optional `shinsou-unified-v1` envelope:

```json
{
  "format": "shinsou-unified-v1",
  "shinsou": [/* PluginIndexEntry */],
  "shuyue": [/* ShuYueRepositoryEntry */]
}
```

`type` (or its `contentType` alias) may be `manga`, `novel`, or `both`. The value can be placed
on a package or an individual source. A source value is preferred; when no usable value is
present, the host resolves the source as `both` for backwards compatibility.

Repository detection is based on the fetched JSON shape. A URL ending in `index.json`, a GitHub
URL, or a local/LAN URL is not evidence of a particular protocol. This keeps ordinary Shinsou
repositories working when their URL happens to contain `index.json`.

For local testing, regenerate the fixture and serve its parent directory:

```sh
SHINSOU_UNIFIED_OUTPUT=/Users/aluoexpiry/project/shinsou_plugin/merged-shuyue \
  node /Users/aluoexpiry/project/shuyue_plugin/build-merged-repository.mjs
python3 -m http.server 18081 --directory /Users/aluoexpiry/project/shinsou_plugin
```

Then add `http://127.0.0.1:18081/merged-shuyue/` (or the Mac's LAN address) in the Extensions
screen to test the legacy unified-v1 contract. To test the reviewed extension-content-v2
repository (including the migrated ShuYue Biquge package), add
`http://127.0.0.1:18081/index.json` instead. The v2 repository now lives at the project root and
serves `plugins/`, `sidecars/`, and the exact migration bindings. The fixture copies scripts into
isolated `shinsou/` and `shuyue/` paths and does not alter either upstream plugin repository.
