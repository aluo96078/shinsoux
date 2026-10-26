"""Extracts IOS_JAVASCRIPTCORE_BOOTSTRAP from the iOS host so the spike reuses it verbatim."""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[3]
SOURCE = ROOT / "composeApp/src/iosMain/kotlin/dev/shinsou/kmp/plugin/IosJavaScriptBootstrap.kt"

text = SOURCE.read_text(encoding="utf-8")
start = text.index('IOS_JAVASCRIPTCORE_BOOTSTRAP: String = """') + len('IOS_JAVASCRIPTCORE_BOOTSTRAP: String = """')
end = text.index('"""', start)
body = text[start:end]
if "${" in body:
    sys.exit("bootstrap contains Kotlin string templates; extraction would be wrong")
pathlib.Path(sys.argv[1]).write_text(body, encoding="utf-8")
